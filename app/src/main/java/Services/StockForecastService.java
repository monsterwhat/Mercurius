package Services;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import Models.Articulos.ArticuloStock;
import Models.Articulos.Articulos;
import Models.PrecisionPronostico;
import Services.pronostico.CalculadoraPronostico;
import Services.pronostico.FlagCalidad;
import Services.pronostico.SerieDiaria;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jakarta.transaction.Transactional.TxType;
import io.quarkus.cache.CacheResult;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * Pronostico de stock por articulo.
 *
 * <p><b>Camino canonico por defecto.</b> La pagina, el API y la exportacion
 * consumen el snapshot de {@code CalculadoraPronostico} + {@code MotorPronostico}
 * (rejilla diaria cero-rellenada, seleccion por horizonte y economia de
 * reposicion). El calculo heredado de media por dias con venta y tendencia
 * multiplicativa queda detras de
 * {@code mercurius.pronostico.canonico-habilitado=false}, solo para rollback
 * durante un ciclo de evaluacion.</p>
 *
 * <p>Igual que {@link ProductPerformanceService}, {@code getSalesHistory} agrega
 * con {@code COALESCE(SUM(ld.cantidad), 0)}: los comprobantes REP (tipo 10) no
 * llevan Cantidad en su LineaDetalle, de modo que un dia con solo lineas REP
 * agrupaba a NULL y el mapeo lanzaba NullPointerException en vez de reportar 0
 * unidades vendidas.</p>
 */
@ApplicationScoped
@Named("stockForecastService")
public class StockForecastService {

    private static final Logger LOG = Logger.getLogger(StockForecastService.class);

    /** Días de historia de ventas con los que se estima la demanda. */
    private static final int DIAS_HISTORIAL = 90;

    /** Máximo de artículos críticos que lista el reporte de salud. */
    private static final int CRITICOS_VISIBLES = 10;

    /**
     * Una fila por artículo activo con el historial de ventas ya agregado y el
     * stock vigente.
     *
     * <p>{@code primera_suma}/{@code segunda_suma} son las mitades de la serie
     * diaria (ordenada por fecha) y {@code suma_cuadrados} alimenta la
     * velocidad; el resto del cálculo se hace en {@link EstadisticaArticulo}
     * para no repetir la aritmética en la página, la exportación y el API.</p>
     */
    private static final String CONSULTA_ESTADISTICAS = """
            WITH historial AS (
                SELECT a.codigo AS articulo,
                       CAST(e.fecha_emision AS date) AS dia,
                       COALESCE(SUM(ld.cantidad), 0) AS cantidad
                FROM comprobantesemitidos f
                JOIN detalle_servicio d ON d.id = f.detalle_servicio_id
                JOIN linea_detalle ld ON ld.detalle_servicio_id = d.id
                JOIN encabezado e ON e.id = f.encabezado_id
                JOIN articulos a ON a.nombre = ld.detalle
                WHERE f.status = true
                  AND e.fecha_emision BETWEEN :inicio AND :fin
                GROUP BY a.codigo, CAST(e.fecha_emision AS date)
            ), ordenado AS (
                SELECT articulo,
                       cantidad,
                       ROW_NUMBER() OVER (PARTITION BY articulo ORDER BY dia) AS posicion,
                       COUNT(*) OVER (PARTITION BY articulo) AS dias
                FROM historial
            ), agregado AS (
                SELECT articulo,
                       MAX(dias) AS dias,
                       SUM(cantidad) AS total,
                       SUM(cantidad) FILTER (WHERE posicion <= dias / 2) AS primera_suma,
                       COUNT(*) FILTER (WHERE posicion <= dias / 2) AS primera_dias,
                       SUM(cantidad) FILTER (WHERE posicion > dias / 2) AS segunda_suma,
                       COUNT(*) FILTER (WHERE posicion > dias / 2) AS segunda_dias,
                       SUM(cantidad * cantidad) AS suma_cuadrados
                FROM ordenado
                GROUP BY articulo
            )
            SELECT a.codigo,
                   a.nombre,
                   dep.nombre,
                   fam.nombre,
                   COALESCE(st.stock, 0),
                   COALESCE(g.dias, 0),
                   COALESCE(g.total, 0),
                   COALESCE(g.primera_suma, 0),
                   COALESCE(g.primera_dias, 0),
                   COALESCE(g.segunda_suma, 0),
                   COALESCE(g.segunda_dias, 0),
                   COALESCE(g.suma_cuadrados, 0)
            FROM articulos a
            LEFT JOIN agregado g ON g.articulo = a.codigo
            LEFT JOIN (
                SELECT articulo_codigo, SUM(cantidad) AS stock
                FROM inventario
                GROUP BY articulo_codigo
            ) st ON st.articulo_codigo = a.codigo
            LEFT JOIN departamento dep ON dep.id = a.departamento_id
            LEFT JOIN familia fam ON fam.id = a.familia_id
            WHERE a.status = true
            ORDER BY a.codigo
            """;

    /**
     * Catalogo activo con stock. Sin ventas ni nombres de factura: la serie
     * diaria se lee aparte por clave de articulo ({@link SeleccionMetodoService#seriesDiariasActivas(int)}).
     */
    private static final String CONSULTA_CATALOGO = """
            SELECT a.codigo,
                   a.nombre,
                   COALESCE(dep.nombre, ''),
                   COALESCE(fam.nombre, ''),
                   COALESCE(st.stock, st2.stock, 0)
            FROM articulos a
            LEFT JOIN articulostock st ON st.articulo_codigo = a.codigo
            LEFT JOIN articulostock st2 ON st2.articulo_codigo IS NULL
                 AND st2.codigo_barra = a.codigobarra
            LEFT JOIN departamento dep ON dep.id = a.departamento_id
            LEFT JOIN familia fam ON fam.id = a.familia_id
            WHERE a.status = true
            ORDER BY a.codigo
            """;

    @Inject @Nonnull
    EntityManager entityManager;

    @Inject @Nonnull
    SeleccionMetodoService seleccionMetodoService;

    @Inject @Nonnull
    ConfiguracionPronosticoService configuracionPronosticoService;

    /** Activa el pipeline canonico (MotorPronostico). false deja el camino legado para rollback. */
    @ConfigProperty(name = "mercurius.pronostico.canonico-habilitado", defaultValue = "true")
    boolean canonicoHabilitado;

    /** Dias de historia con los que se estima la demanda canonica. */
    @ConfigProperty(name = "mercurius.pronostico.historial-dias", defaultValue = "180")
    int historialDiasConfigurado;

    /** Vigencia de la lectura del catálogo en memoria (0 = sin caché). */
    @ConfigProperty(name = "mercurius.pronostico.catalogo-ttl-ms", defaultValue = "60000")
    long vigenciaCatalogoMs;

    /** Snapshot canonico por horizonte, para que pagina, API y exportacion compartan la misma corrida. */
    private final AtomicReference<SnapshotCanonico> snapshotCanonico = new AtomicReference<>();

    /** Última lectura agregada del catálogo y hasta cuándo vale. */
    private final AtomicReference<CatalogoVigente> catalogoVigente = new AtomicReference<>();

    private record CatalogoVigente(List<EstadisticaArticulo> estadisticas, long expiraEn) {
    }

    @Transactional(TxType.SUPPORTS)
    @Nonnull
    public List<ProductForecast> generateForecast(@Nonnull Long articuloId, int forecastDays) {
        if (canonicoHabilitado) {
            return generarForecastCanonico(articuloId, forecastDays);
        }
        Articulos articulo = entityManager.find(Articulos.class, articuloId);
        if (articulo == null) return Collections.emptyList();

        List<SalesData> salesHistory = getSalesHistory(articuloId, 90);
        
        if (salesHistory.isEmpty()) {
            return Collections.emptyList();
        }

        BigDecimal averageDailySales = calculateAverageDailySales(salesHistory);
        BigDecimal salesVelocity = calculateSalesVelocity(salesHistory);
        BigDecimal trendFactor = calculateTrendFactor(salesHistory);

        int currentStock = getCurrentStock(articuloId);
        int reorderPoint = calculateReorderPoint(averageDailySales, 7);
        int optimalStock = calculateOptimalStock(averageDailySales, 30);
        int safetyStock = calculateSafetyStock(salesHistory);

        List<ProductForecast> forecasts = new ArrayList<>();
        BigDecimal runningStock = BigDecimal.valueOf(currentStock);
        BigDecimal predictedSales = averageDailySales;

        for (int day = 1; day <= forecastDays; day++) {
            predictedSales = predictedSales.multiply(trendFactor);
            
            runningStock = runningStock.subtract(predictedSales);
            
            boolean shouldReorder = runningStock.compareTo(BigDecimal.valueOf(reorderPoint)) <= 0;
            
            LocalDate forecastDate = LocalDate.now().plusDays(day);
            
            forecasts.add(new ProductForecast(
                articulo.getCodigo(),
                articulo.getNombre(),
                forecastDate,
                currentStock,
                predictedSales.setScale(0, RoundingMode.HALF_UP).intValue(),
                Math.max(0, runningStock.setScale(0, RoundingMode.HALF_UP).intValue()),
                shouldReorder,
                averageDailySales.setScale(2, RoundingMode.HALF_UP),
                salesVelocity.setScale(2, RoundingMode.HALF_UP)
            ));
        }

        return forecasts;
    }

    @Transactional(TxType.SUPPORTS)
    @Nonnull
    public List<ProductForecast> generateBulkForecast(int daysToForecast) {
        if (canonicoHabilitado) {
            return pronosticosCanonicos(daysToForecast);
        }
        return pronosticosDe(estadisticasCatalogo(), daysToForecast);
    }

    /**
     * El mismo pronóstico sobre una lectura ya hecha del catálogo.
     *
     * <p>La exportación necesita el pronóstico y los contadores de salud en la
     * misma respuesta: con las dos llamadas de siempre pagaba dos veces la
     * consulta agregada. Así lee el catálogo una vez y deriva las dos cosas.</p>
     */
    @Nonnull
    public List<ProductForecast> generateBulkForecast(
            @Nonnull List<EstadisticaArticulo> estadisticas, int daysToForecast) {
        if (canonicoHabilitado) {
            return pronosticosCanonicos(daysToForecast);
        }
        return pronosticosDe(estadisticas, daysToForecast);
    }

    @Nonnull
    private static List<ProductForecast> pronosticosDe(
            @Nonnull List<EstadisticaArticulo> estadisticas, int daysToForecast) {
        List<ProductForecast> allForecasts = new ArrayList<>(estadisticas.size());

        for (EstadisticaArticulo estadistica : estadisticas) {
            Proyeccion proyeccion = estadistica.proyectar(daysToForecast);
            if (proyeccion == null) {
                continue;
            }
            allForecasts.add(new ProductForecast(
                estadistica.codigo(),
                estadistica.nombre(),
                LocalDate.now().plusDays(proyeccion.dia()),
                estadistica.stock(),
                proyeccion.ventaPronosticada(),
                proyeccion.stockPronosticado(),
                true,
                estadistica.promedioDiario().setScale(2, RoundingMode.HALF_UP),
                estadistica.velocidadVentas().setScale(2, RoundingMode.HALF_UP)
            ));
        }

        return allForecasts;
    }

    @Transactional(TxType.SUPPORTS)
    @Nonnull
    public DemandPrediction predictDemand(@Nonnull Long articuloId, int daysToPredict) {
        if (canonicoHabilitado) {
            return predecirDemandaCanonica(articuloId, daysToPredict);
        }
        List<SalesData> salesHistory = getSalesHistory(articuloId, 180);
        
        if (salesHistory.isEmpty()) {
            return new DemandPrediction(articuloId, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, "INSUFFICIENT_DATA");
        }

        BigDecimal avgDaily = calculateAverageDailySales(salesHistory);
        BigDecimal velocity = calculateSalesVelocity(salesHistory);
        BigDecimal trend = calculateTrendFactor(salesHistory);
        
        BigDecimal predictedDemand = avgDaily.multiply(BigDecimal.valueOf(daysToPredict));
        
        BigDecimal seasonalFactor = calculateSeasonalFactor(salesHistory);
        
        predictedDemand = predictedDemand.multiply(seasonalFactor);

        String confidence;
        if (salesHistory.size() >= 90) {
            confidence = "HIGH";
        } else if (salesHistory.size() >= 30) {
            confidence = "MEDIUM";
        } else {
            confidence = "LOW";
        }

        return new DemandPrediction(
            articuloId,
            predictedDemand.setScale(2, RoundingMode.HALF_UP),
            avgDaily.setScale(2, RoundingMode.HALF_UP),
            trend.setScale(2, RoundingMode.HALF_UP),
            confidence
        );
    }

    @Transactional(TxType.SUPPORTS)
    @Nonnull
    public InventoryHealthReport getInventoryHealthReport() {
        if (canonicoHabilitado) {
            return snapshotCanonico(30).salud();
        }
        return saludDe(estadisticasCatalogo());
    }

    /**
     * Salud del inventario para el mismo horizonte que muestra el reporte.
     * La pagina y la exportacion la piden aqui para no mezclar un horizonte
     * con otro.
     */
    @Transactional(TxType.SUPPORTS)
    @Nonnull
    public InventoryHealthReport salud(int horizonteDias) {
        if (canonicoHabilitado) {
            return snapshotCanonico(horizonteDias).salud();
        }
        return saludDe(estadisticasCatalogo());
    }

    @Transactional(TxType.SUPPORTS)
    @Nullable
    public ReorderRecommendation getReorderRecommendation(@Nonnull Long articuloId) {
        if (canonicoHabilitado) {
            return recomendacionCanonica(articuloId);
        }
        Articulos articulo = entityManager.find(Articulos.class, articuloId);
        if (articulo == null) return null;

        int currentStock = getCurrentStock(articuloId);
        List<SalesData> salesHistory = getSalesHistory(articuloId, 90);
        
        if (salesHistory.isEmpty()) {
            return new ReorderRecommendation(
                articuloId,
                articulo.getNombre(),
                currentStock,
                0,
                0,
                "NO_SALES_DATA",
                BigDecimal.ZERO
            );
        }

        BigDecimal avgDaily = calculateAverageDailySales(salesHistory);
        BigDecimal velocity = calculateSalesVelocity(salesHistory);
        
        int daysUntilStockout = avgDaily.compareTo(BigDecimal.ZERO) > 0 
            ? currentStock / avgDaily.setScale(0, RoundingMode.HALF_UP).intValue()
            : 999;

        int recommendedReorder = calculateOptimalStock(avgDaily, 30) - currentStock;
        
        String status;
        if (currentStock <= 0) {
            status = "OUT_OF_STOCK";
        } else if (daysUntilStockout <= 3) {
            status = "CRITICAL";
        } else if (daysUntilStockout <= 7) {
            status = "LOW";
        } else if (daysUntilStockout <= 14) {
            status = "MODERATE";
        } else {
            status = "OPTIMAL";
        }

        return new ReorderRecommendation(
            articuloId,
            articulo.getNombre(),
            currentStock,
            Math.max(0, recommendedReorder),
            daysUntilStockout,
            status,
            avgDaily.setScale(2, RoundingMode.HALF_UP)
        );
    }

    /**
     * Estadística de venta y stock de TODO el catálogo en una sola consulta.
     *
     * <p>Sustituye a las tres lecturas que hacía el pronóstico: las entidades
     * {@code Articulos} (con su colección EAGER de precios), el historial diario
     * de ventas y el stock por artículo. Devuelve una fila por artículo activo
     * (aunque no tenga ventas) con los agregados que necesitan el pronóstico y
     * el reporte de salud.</p>
     *
     * <p>El historial son los últimos 90 días de líneas de comprobantes
     * emitidos, asociadas al artículo por nombre (así lo hace
     * {@link #getSalesHistory(Long, int)}). El stock es la suma de
     * {@code inventario}, igual que {@link #getCurrentStock(Long)}. La ventana
     * de {@value #DIAS_HISTORIAL} días no depende del horizonte pedido: es la
     * historia con la que se estima la demanda.</p>
     *
     * <p>Si la lectura falla se registra y se devuelve una lista vacía: el
     * reporte se degrada a "sin datos" en vez de responder 500.</p>
     *
     * <p><b>Vigencia.</b> La lectura se guarda en memoria durante
     * {@code mercurius.pronostico.catalogo-ttl-ms} (60 s por defecto) y la
     * comparten todas las variantes del reporte (sección, orden, página,
     * filtros) y la exportación: paginar u ordenar no vuelve a pagar los ~3 s
     * de consulta. El costo es que el stock mostrado puede tener hasta ese
     * tiempo de antigüedad. Un fallo de lectura nunca se guarda, y con
     * {@code 0} la caché queda desactivada.</p>
     */
    @Transactional(TxType.SUPPORTS)
    @Nonnull
    public List<EstadisticaArticulo> estadisticasCatalogo() {
        long ahora = System.currentTimeMillis();
        CatalogoVigente vigente = catalogoVigente.get();
        if (vigente != null && vigente.expiraEn() > ahora) {
            return vigente.estadisticas();
        }

        // Una sola lectura a la vez: sin esto, N pestañas abriendo el reporte a
        // la vez lanzan N escaneos de 24M de filas en paralelo.
        synchronized (this) {
            vigente = catalogoVigente.get();
            long solicitado = System.currentTimeMillis();
            if (vigente != null && vigente.expiraEn() > solicitado) {
                return vigente.estadisticas();
            }
            List<EstadisticaArticulo> leidas = leerCatalogo();
            if (!leidas.isEmpty() && vigenciaCatalogoMs > 0) {
                catalogoVigente.set(new CatalogoVigente(
                        List.copyOf(leidas), solicitado + vigenciaCatalogoMs));
            }
            return leidas;
        }
    }

    /** Lectura agregada del catálogo, sin caché (ver estadisticasCatalogo). */
    @Nonnull
    private List<EstadisticaArticulo> leerCatalogo() {
        LocalDateTime inicio = LocalDate.now().minusDays(DIAS_HISTORIAL).atStartOfDay();
        LocalDateTime fin = LocalDate.now().atTime(23, 59, 59);

        List<Object[]> filas;
        try {
            filas = entityManager.createNativeQuery(CONSULTA_ESTADISTICAS)
                    .setParameter("inicio", inicio)
                    .setParameter("fin", fin)
                    .getResultList();
        } catch (RuntimeException e) {
            LOG.warn("estadisticasCatalogo: no se pudo leer historial/stock ("
                    + e.getMessage() + "); el reporte saldra vacio", e);
            return List.of();
        }

        List<EstadisticaArticulo> estadisticas = new ArrayList<>(filas.size());
        for (Object[] fila : filas) {
            estadisticas.add(new EstadisticaArticulo(
                    numeroLargo(fila[0]),
                    texto(fila[1]),
                    texto(fila[2]),
                    texto(fila[3]),
                    numeroEntero(fila[4]),
                    numeroEntero(fila[5]),
                    decimal(fila[6]),
                    decimal(fila[7]),
                    numeroEntero(fila[8]),
                    decimal(fila[9]),
                    numeroEntero(fila[10]),
                    decimal(fila[11])));
        }
        return estadisticas;
    }

    /**
     * Todo lo que la página de pronósticos necesita, en una sola lectura.
     *
     * <p>En lugar de hidratar el catálogo dos veces y paginar 200k mapas en
     * memoria, la consulta agregada se recorre una vez: de ahí salen los
     * selectores, los contadores de salud y <b>solo</b> las filas de la
     * sección pedida. El controlador ordena y pagina esa lista compacta.</p>
     *
     * @param dias        horizonte en días (7, 14, 30 o 60)
     * @param seccion     pronosticos | reorden | salud
     * @param departamento filtro por nombre exacto (vacío = todos)
     * @param familia     filtro por nombre exacto (vacío = todas)
     */
    @Transactional(TxType.SUPPORTS)
    @Nonnull
    public ReportePronosticos reportePronosticos(int dias,
                                                 @Nullable String seccion,
                                                 @Nullable String departamento,
                                                 @Nullable String familia) {
        if (canonicoHabilitado) {
            return reportePronosticosCanonico(dias, seccion, departamento, familia);
        }
        List<EstadisticaArticulo> estadisticas = estadisticasCatalogo();
        String depto = normalizarFiltro(departamento);
        String fam = normalizarFiltro(familia);
        boolean vistaSalud = "salud".equals(seccion);

        SortedSet<String> departamentos = new TreeSet<>();
        SortedSet<String> familias = new TreeSet<>();
        for (EstadisticaArticulo e : estadisticas) {
            if (!e.departamento().isBlank()) {
                departamentos.add(e.departamento());
            }
            if (!e.familia().isBlank()) {
                familias.add(e.familia());
            }
        }

        List<FilaPronostico> filas = new ArrayList<>();
        if (!vistaSalud) {
            for (EstadisticaArticulo e : estadisticas) {
                if (!coincide(e.departamento(), e.familia(), depto, fam)) {
                    continue;
                }
                Proyeccion proyeccion = e.proyectar(dias);
                if (proyeccion == null) {
                    continue;
                }
                filas.add(new FilaPronostico(
                        e.codigo(),
                        e.nombre(),
                        e.departamento(),
                        e.familia(),
                        e.stock(),
                        proyeccion.ventaPronosticada(),
                        diasRestantes(e),
                        cantidadSugerida(e, dias),
                        proyeccion.stockPronosticado() <= 0 ? "Comprar" : "Pronto",
                        null, null, null, ""));
            }
        }

        // La sección de salud lista los primeros 10 críticos del catálogo (no
        // los 10 primeros del filtro): el filtro se aplica después, igual que
        // cuando la página leía criticalItems() y lo cruzaba con el grupo.
        List<FilaCritico> criticos = new ArrayList<>();
        if (vistaSalud) {
            for (EstadisticaArticulo e : estadisticas) {
                if (criticos.size() >= CRITICOS_VISIBLES) {
                    break;
                }
                if (!esCritico(e)) {
                    continue;
                }
                if (coincide(e.departamento(), e.familia(), depto, fam)) {
                    criticos.add(new FilaCritico(e.codigo(), e.nombre(),
                            e.departamento(), e.familia()));
                }
            }
        }

        return new ReportePronosticos(
                List.copyOf(departamentos),
                List.copyOf(familias),
                saludDe(estadisticas),
                filas,
                criticos);
    }

    /**
     * Agregados de venta y stock de un articulo para el camino heredado.
     *
     * <p><b>No es el contrato de produccion.</b> {@code promedioDiario()} divide
     * por dias CON venta, que es justamente el defecto que corrige la rejilla
     * canonica. Esta estructura solo se usa con
     * {@code mercurius.pronostico.canonico-habilitado=false}.</p>
     */
    public record EstadisticaArticulo(
            long codigo,
            @Nonnull String nombre,
            @Nonnull String departamento,
            @Nonnull String familia,
            int stock,
            int diasConVenta,
            @Nonnull BigDecimal totalVendido,
            @Nonnull BigDecimal primeraMitad,
            int diasPrimeraMitad,
            @Nonnull BigDecimal segundaMitad,
            int diasSegundaMitad,
            @Nonnull BigDecimal sumaCuadrados) {

        /** Promedio diario de los días CON venta, con cuatro decimales. */
        @Nonnull
        public BigDecimal promedioDiario() {
            if (diasConVenta <= 0) {
                return BigDecimal.ZERO;
            }
            return totalVendido.divide(BigDecimal.valueOf(diasConVenta), 4, RoundingMode.HALF_UP);
        }

        /**
         * Cociente entre la segunda y la primera mitad de la serie diaria.
         *
         * <p>Con menos de siete días la tendencia no es estimable y se usa 1
         * (pronóstico plano), igual que el recorrido anterior.</p>
         */
        @Nonnull
        public BigDecimal factorTendencia() {
            if (diasConVenta < 7 || diasPrimeraMitad <= 0 || diasSegundaMitad <= 0) {
                return BigDecimal.ONE;
            }
            BigDecimal primera = primeraMitad.divide(
                    BigDecimal.valueOf(diasPrimeraMitad), 4, RoundingMode.HALF_UP);
            if (primera.compareTo(BigDecimal.ZERO) == 0) {
                return BigDecimal.ONE;
            }
            BigDecimal segunda = segundaMitad.divide(
                    BigDecimal.valueOf(diasSegundaMitad), 4, RoundingMode.HALF_UP);
            return segunda.divide(primera, 4, RoundingMode.HALF_UP);
        }

        /** 1 - coeficiente de variación de la serie diaria (como antes). */
        @Nonnull
        public BigDecimal velocidadVentas() {
            if (diasConVenta < 2) {
                return BigDecimal.ONE;
            }
            double n = diasConVenta;
            double media = totalVendido.doubleValue() / n;
            if (media == 0d) {
                return BigDecimal.ONE;
            }
            double varianza = (sumaCuadrados.doubleValue() / n) - (media * media);
            double desviacion = Math.sqrt(Math.max(0d, varianza));
            return BigDecimal.ONE.add(BigDecimal.valueOf(desviacion / media).negate());
        }

        /** Punto de reorden a siete días: media redondeada por 7, más uno. */
        public int puntoReorden() {
            return promedioDiario().setScale(0, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(7)).intValue() + 1;
        }

        /** Stock óptimo a treinta días: media redondeada por 30. */
        public int stockOptimo() {
            return promedioDiario().setScale(0, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(30)).intValue();
        }

        /**
         * Primera proyección diaria que alcanza el punto de reorden.
         *
         * <p>Es el mismo recorrido día a día que hacía el pronóstico: la venta
         * del día se acumula multiplicando por la tendencia y el stock baja
         * hasta cruzar el punto de reorden. Sin ventas no hay pronóstico.</p>
         *
         * @return la proyección del primer día que cruza, o {@code null}
         */
        @Nullable
        public Proyeccion proyectar(int dias) {
            if (diasConVenta <= 0) {
                return null;
            }
            BigDecimal promedio = promedioDiario();
            BigDecimal tendencia = factorTendencia();
            int reorden = puntoReorden();
            BigDecimal venta = promedio;
            BigDecimal stockProyectado = BigDecimal.valueOf(stock);

            for (int dia = 1; dia <= dias; dia++) {
                venta = venta.multiply(tendencia);
                stockProyectado = stockProyectado.subtract(venta);
                if (stockProyectado.compareTo(BigDecimal.valueOf(reorden)) <= 0) {
                    return new Proyeccion(dia,
                            venta.setScale(0, RoundingMode.HALF_UP).intValue(),
                            Math.max(0, stockProyectado.setScale(0, RoundingMode.HALF_UP).intValue()));
                }
            }
            return null;
        }
    }

    /** Día en que el stock proyectado cruza el punto de reorden. */
    public record Proyeccion(int dia, int ventaPronosticada, int stockPronosticado) {
    }

    /** Fila de la tabla de pronósticos o de sugerencias de reorden. */
    public record FilaPronostico(
            long articuloCodigo,
            @Nonnull String articulo,
            @Nonnull String departamento,
            @Nonnull String familia,
            int stockActual,
            int demandaEstimada,
            @Nonnull String diasRestantes,
            int cantidadSugerida,
            @Nonnull String recomendacion,
            @Nullable String metodo,
            @Nullable Double mase,
            @Nullable Double sesgo,
            @Nonnull String calidadFlags) {
    }

    /** Fila de la sección de salud del inventario. */
    public record FilaCritico(
            long articuloCodigo,
            @Nonnull String articulo,
            @Nonnull String departamento,
            @Nonnull String familia) {
    }

    /** Lectura completa de la página de pronósticos. */
    public record ReportePronosticos(
            @Nonnull List<String> departamentos,
            @Nonnull List<String> familias,
            @Nonnull InventoryHealthReport salud,
            @Nonnull List<FilaPronostico> filas,
            @Nonnull List<FilaCritico> criticos) {
    }

    /** Contadores de salud sobre la misma lectura agregada. */
    @Nonnull
    public static InventoryHealthReport saludDe(@Nonnull List<EstadisticaArticulo> estadisticas) {
        int optimo = 0;
        int bajo = 0;
        int exceso = 0;
        int sinStock = 0;
        List<String> criticos = new ArrayList<>();

        for (EstadisticaArticulo e : estadisticas) {
            if (e.stock() <= 0) {
                sinStock++;
                if (criticos.size() < CRITICOS_VISIBLES) {
                    criticos.add(e.nombre());
                }
            } else if (e.diasConVenta() <= 0) {
                optimo++;
            } else {
                int reorden = e.puntoReorden();
                if (e.stock() < reorden) {
                    bajo++;
                    if (e.stock() < reorden / 2 && criticos.size() < CRITICOS_VISIBLES) {
                        criticos.add(e.nombre());
                    }
                } else if (e.stock() > e.stockOptimo() * 2) {
                    exceso++;
                } else {
                    optimo++;
                }
            }
        }

        return new InventoryHealthReport(
                estadisticas.size(), optimo, bajo, exceso, sinStock, criticos);
    }

    /** ¿El artículo entra en la lista de críticos del reporte de salud? */
    private static boolean esCritico(@Nonnull EstadisticaArticulo e) {
        if (e.stock() <= 0) {
            return true;
        }
        if (e.diasConVenta() <= 0) {
            return false;
        }
        return e.stock() < e.puntoReorden() / 2;
    }

    /** Días de cobertura al ritmo promedio (misma lectura que la tabla). */
    @Nonnull
    private static String diasRestantes(@Nonnull EstadisticaArticulo e) {
        // La tabla siempre mostró el promedio con dos decimales: se conserva
        // ese redondeo para que los días de cobertura no cambien de valor.
        BigDecimal promedio = e.promedioDiario().setScale(2, RoundingMode.HALF_UP);
        if (promedio.compareTo(BigDecimal.ZERO) <= 0) {
            return "-";
        }
        int dias = BigDecimal.valueOf(e.stock()).divide(promedio, 0, RoundingMode.DOWN).intValue();
        return String.valueOf(Math.max(0, dias));
    }

    /** Reposición sugerida bruta para el horizonte pedido. */
    private static int cantidadSugerida(@Nonnull EstadisticaArticulo e, int dias) {
        BigDecimal promedio = e.promedioDiario().setScale(2, RoundingMode.HALF_UP);
        return Math.max(0, promedio.multiply(BigDecimal.valueOf(dias))
                .setScale(0, RoundingMode.HALF_UP).intValue() - e.stock());
    }

    /** Diagnostico de demanda historica que no se puede atribuir a un articulo unico. */
    public record DiagnosticoDemanda(long lineasSinArticulo,
                                     double unidadesSinArticulo,
                                     long nombresSinArticulo,
                                     long lineasConNombreAmbiguo) {
    }

    /**
     * Cuenta las lineas de factura de la ventana cuya {@code detalle} no
     * corresponde a exactamente un articulo activo.
     *
     * <p>El pipeline canonico ya no usa nombres para pronosticar: la demanda
     * sale de movimientos de inventario por clave. Este diagnostico existe para
     * que el historical sin movimiento de inventario sea visible y medible en
     * vez de desaparecer en silencio. Un nombre repetido no se atribuye a
     * ninguno de los dos: sumarlo contaminaria a ambos.</p>
     */
    @Transactional(TxType.SUPPORTS)
    @Nonnull
    public DiagnosticoDemanda diagnosticoDemandaHistorica(int dias) {
        int ventana = dias <= 0 ? Math.max(1, historialDiasConfigurado) : dias;
        LocalDateTime inicio = LocalDate.now().minusDays(ventana).atStartOfDay();
        LocalDateTime fin = LocalDate.now().atTime(23, 59, 59);
        try {
            Object[] fila = (Object[]) entityManager.createNativeQuery("""
                            SELECT
                                COUNT(*) FILTER (WHERE (
                                    SELECT COUNT(*) FROM articulos a2
                                    WHERE a2.nombre = ld.detalle AND a2.status = true) = 0),
                                COALESCE(SUM(ABS(ld.cantidad)) FILTER (WHERE (
                                    SELECT COUNT(*) FROM articulos a2
                                    WHERE a2.nombre = ld.detalle AND a2.status = true) = 0), 0),
                                COUNT(DISTINCT ld.detalle) FILTER (WHERE (
                                    SELECT COUNT(*) FROM articulos a2
                                    WHERE a2.nombre = ld.detalle AND a2.status = true) = 0),
                                COUNT(*) FILTER (WHERE (
                                    SELECT COUNT(*) FROM articulos a2
                                    WHERE a2.nombre = ld.detalle AND a2.status = true) > 1)
                            FROM comprobantesemitidos f
                            JOIN detalle_servicio d ON d.id = f.detalle_servicio_id
                            JOIN linea_detalle ld ON ld.detalle_servicio_id = d.id
                            JOIN encabezado e ON e.id = f.encabezado_id
                            WHERE f.status = true
                              AND e.fecha_emision BETWEEN :inicio AND :fin
                            """)
                    .setParameter("inicio", inicio)
                    .setParameter("fin", fin)
                    .getSingleResult();
            DiagnosticoDemanda diagnostico = new DiagnosticoDemanda(
                    numeroLargo(fila[0]), decimal(fila[1]).doubleValue(),
                    numeroLargo(fila[2]), numeroLargo(fila[3]));
            if (diagnostico.lineasSinArticulo() > 0 || diagnostico.lineasConNombreAmbiguo() > 0) {
                LOG.warnf("Demanda historica no atribuible: lineas=%d unidades=%.2f nombres=%d ambiguas=%d",
                        diagnostico.lineasSinArticulo(), diagnostico.unidadesSinArticulo(),
                        diagnostico.nombresSinArticulo(), diagnostico.lineasConNombreAmbiguo());
            }
            return diagnostico;
        } catch (RuntimeException e) {
            LOG.error("diagnosticoDemandaHistorica: no se pudo medir la demanda sin articulo; "
                    + "no se reporta cero", e);
            throw e;
        }
    }

    /** Snapshot canonico compartido por pagina, API y exportacion. */
    private record SnapshotCanonico(int horizonte,
                                    long expiraEn,
                                    @Nonnull List<CalculadoraPronostico.Resultado> resultados,
                                    @Nonnull InventoryHealthReport salud) {
    }

    /**
     * Snapshot canonico de todo el catalogo para un horizonte. La lectura de
     * catalogo y series se comparte entre la pagina, el API y la exportacion
     * durante {@code mercurius.pronostico.catalogo-ttl-ms}; sin esto, abrir la
     * pagina pagaria dos escaneos completos.
     */
    @Nonnull
    private SnapshotCanonico snapshotCanonico(int horizonteDias) {
        int horizonte = normalizarHorizonte(horizonteDias);
        long ahora = System.currentTimeMillis();
        SnapshotCanonico vigente = snapshotCanonico.get();
        if (vigente != null && vigente.horizonte() == horizonte && vigente.expiraEn() > ahora) {
            return vigente;
        }
        synchronized (this) {
            vigente = snapshotCanonico.get();
            long solicitado = System.currentTimeMillis();
            if (vigente != null && vigente.horizonte() == horizonte && vigente.expiraEn() > solicitado) {
                return vigente;
            }
            SnapshotCanonico leido = construirSnapshot(horizonte);
            if (vigenciaCatalogoMs > 0) {
                snapshotCanonico.set(new SnapshotCanonico(horizonte,
                        solicitado + vigenciaCatalogoMs, leido.resultados(), leido.salud()));
            }
            return leido;
        }
    }

    @Nonnull
    private SnapshotCanonico construirSnapshot(int horizonte) {
        List<Object[]> filas;
        try {
            filas = entityManager.createNativeQuery(CONSULTA_CATALOGO).getResultList();
        } catch (RuntimeException e) {
            LOG.error("snapshotCanonico: no se pudo leer el catalogo; no se degrada a "
                    + "'sin datos'"
                    + " | horizonte=" + horizonte, e);
            throw new IllegalStateException(
                    "No se pudo leer el catálogo para el pronóstico: " + e.getMessage(), e);
        }

        Map<Long, List<SerieDiaria>> series;
        try {
            series = seleccionMetodoService.seriesDiariasActivas(historialDiasConfigurado);
        } catch (RuntimeException e) {
            LOG.error("snapshotCanonico: no se pudo leer la demanda diaria; no se pronostica "
                    + "con historia vacia"
                    + " | horizonte=" + horizonte, e);
            throw new IllegalStateException(
                    "No se pudo leer la demanda diaria para el pronóstico: " + e.getMessage(), e);
        }

        Map<Long, PrecisionPronostico> selecciones;
        try {
            selecciones = seleccionMetodoService.selecciones(horizonte);
        } catch (RuntimeException e) {
            LOG.error("snapshotCanonico: no se pudo leer la seleccion de metodos; no se "
                    + "sustituye por un respaldo silencioso"
                    + " | horizonte=" + horizonte, e);
            throw new IllegalStateException(
                    "No se pudo leer la selección de métodos para el pronóstico: "
                            + e.getMessage(), e);
        }

        CalculadoraPronostico.Opciones opciones = opcionesCanonicas();
        Map<Long, BigDecimal> ordenesAbiertas = ordenesCompraAbiertasPorArticulo();
        List<CalculadoraPronostico.Resultado> resultados = new ArrayList<>(filas.size());
        for (Object[] fila : filas) {
            long codigo = numeroLargo(fila[0]);
            CalculadoraPronostico.Articulo articulo = new CalculadoraPronostico.Articulo(
                    codigo, texto(fila[1]), texto(fila[2]), texto(fila[3]), numeroEntero(fila[4]));
            List<SerieDiaria> serie = series.getOrDefault(codigo, List.of());
            double abiertas = ordenesAbiertas.getOrDefault(codigo, BigDecimal.ZERO).doubleValue();
            CalculadoraPronostico.Resultado resultado = CalculadoraPronostico.calcular(
                    articulo, serie, horizonte, selecciones.get(codigo), opciones,
                    false, false, abiertas);
            resultados.add(resultado);
        }

        InventoryHealthReport salud = saludCanonica(resultados);
        LOG.infof("snapshotCanonico: horizonte=%d articulos=%d conHistoria=%d metodoTop=%s",
                horizonte, resultados.size(),
                resultados.stream().filter(r -> !r.diario().isEmpty() && r.diasConDemanda() > 0).count(),
                metodoFrecuente(resultados));
        return new SnapshotCanonico(horizonte, 0, List.copyOf(resultados), salud);
    }

    /** Unidades ya pedidas y no recibidas, por articulo. */
    @Nonnull
    private Map<Long, BigDecimal> ordenesCompraAbiertasPorArticulo() {
        try {
            List<Object[]> filas = entityManager.createQuery("""
                            SELECT d.articulo.codigo, SUM(d.cantidad)
                            FROM OrdenCompraDetalle d
                            WHERE d.ordenCompra.status = true
                              AND d.ordenCompra.estado IN ('BORRADOR', 'ENVIADA', 'CONFIRMADA')
                            GROUP BY d.articulo.codigo
                            """, Object[].class)
                    .getResultList();
            Map<Long, BigDecimal> abiertas = new HashMap<>();
            for (Object[] fila : filas) {
                abiertas.put(numeroLargo(fila[0]), decimal(fila[1]));
            }
            return Map.copyOf(abiertas);
        } catch (RuntimeException e) {
            LOG.error("No se pudieron leer las ordenes de compra abiertas; no se ignora el "
                    + "compromiso de stock", e);
            throw new IllegalStateException(
                    "No se pudieron leer las órdenes de compra abiertas: " + e.getMessage(), e);
        }
    }

    /** Unidades abiertas de un articulo, para la ficha individual. */
    private double ordenesCompraAbiertas(Long articuloId) {
        return ordenesCompraAbiertasPorArticulo()
                .getOrDefault(articuloId, BigDecimal.ZERO)
                .doubleValue();
    }

    /** Opciones economicas de reposicion desde configuracion. */
    @Nonnull
    private CalculadoraPronostico.Opciones opcionesCanonicas() {
        ConfiguracionPronosticoService.ParametrosPronostico parametros =
                configuracionPronosticoService.parametros();
        return new CalculadoraPronostico.Opciones(
                parametros.nivelServicio(),
                parametros.costoFaltante(),
                parametros.costoMantenimiento(),
                parametros.plazoReposicionDias(),
                parametros.cantidadMinimaPedido(),
                parametros.multiploPedido());
    }

    @Nonnull
    private List<ProductForecast> pronosticosCanonicos(int daysToForecast) {
        int horizonte = normalizarHorizonte(daysToForecast);
        SnapshotCanonico snapshot = snapshotCanonico(horizonte);
        List<ProductForecast> salida = new ArrayList<>(snapshot.resultados().size());
        for (CalculadoraPronostico.Resultado resultado : snapshot.resultados()) {
            if (resultado.diasConDemanda() <= 0 || resultado.diario().isEmpty()) {
                continue;
            }
            salida.add(productoForecast(resultado));
        }
        return salida;
    }

    @Nonnull
    private List<ProductForecast> generarForecastCanonico(@Nonnull Long articuloId, int forecastDays) {
        int horizonte = normalizarHorizonte(forecastDays);
        Articulos articulo = entityManager.find(Articulos.class, articuloId);
        if (articulo == null) {
            return Collections.emptyList();
        }
        List<SerieDiaria> serie = seleccionMetodoService.serieDiaria(articuloId, historialDiasConfigurado);
        if (serie == null || serie.isEmpty()) {
            return Collections.emptyList();
        }
        CalculadoraPronostico.Resultado resultado = CalculadoraPronostico.calcular(
                new CalculadoraPronostico.Articulo(articulo.getCodigo(), articulo.getNombre(),
                        "", "", getCurrentStock(articuloId)),
                serie, horizonte,
                seleccionMetodoService.seleccionPara(articuloId, horizonte).orElse(null),
                opcionesCanonicas(), false, true, ordenesCompraAbiertas(articuloId));

        List<ProductForecast> forecast = new ArrayList<>(horizonte);
        double acumulado = 0.0;
        for (int dia = 1; dia <= horizonte; dia++) {
            double demandaDia = resultado.diario().get(dia - 1);
            acumulado += demandaDia;
            int stockProyectado = Math.max(0,
                    (int) Math.round(resultado.articulo().stock() - acumulado));
            boolean reordenar = stockProyectado <= resultado.puntoReorden();
            forecast.add(new ProductForecast(
                    resultado.articulo().codigo(), resultado.articulo().nombre(),
                    LocalDate.now().plusDays(dia), resultado.articulo().stock(),
                    (int) Math.round(demandaDia), stockProyectado, reordenar,
                    BigDecimal.valueOf(resultado.promedioDiarioCalendario())
                            .setScale(2, RoundingMode.HALF_UP),
                    BigDecimal.valueOf(1.0 - resultado.coeficienteVariacion())
                            .setScale(2, RoundingMode.HALF_UP),
                    resultado.metodo() == null ? null : resultado.metodo().name(),
                    resultado.mase(), resultado.sesgo(),
                    etiquetas(resultado.flags()), resultado.demandaTotal(),
                    resultado.puntoReorden(), resultado.cantidadSugerida()));
        }
        return forecast;
    }

    @Nonnull
    private ProductForecast productoForecast(CalculadoraPronostico.Resultado resultado) {
        List<Double> diario = resultado.diario();
        int demandaUltimoDia = diario.isEmpty() ? 0
                : (int) Math.round(diario.get(diario.size() - 1));
        int stockProyectado = Math.max(0,
                (int) Math.round(resultado.articulo().stock() - resultado.demandaTotal()));
        boolean reordenar = resultado.cantidadSugerida() > 0
                || stockProyectado <= resultado.puntoReorden();
        BigDecimal velocidad = BigDecimal.valueOf(
                1.0 - resultado.coeficienteVariacion());
        return new ProductForecast(
                resultado.articulo().codigo(), resultado.articulo().nombre(),
                LocalDate.now().plusDays(resultado.horizonte()),
                resultado.articulo().stock(), demandaUltimoDia, stockProyectado, reordenar,
                BigDecimal.valueOf(resultado.promedioDiarioCalendario())
                        .setScale(2, RoundingMode.HALF_UP),
                velocidad.setScale(2, RoundingMode.HALF_UP),
                resultado.metodo() == null ? null : resultado.metodo().name(),
                resultado.mase(), resultado.sesgo(),
                etiquetas(resultado.flags()), resultado.demandaTotal(),
                resultado.puntoReorden(), resultado.cantidadSugerida());
    }

    @Nonnull
    private DemandPrediction predecirDemandaCanonica(@Nonnull Long articuloId, int daysToPredict) {
        int horizonte = normalizarHorizonte(daysToPredict);
        Articulos articulo = entityManager.find(Articulos.class, articuloId);
        if (articulo == null) {
            LOG.warnf("predictDemand: el articulo %d no existe; se devuelve INSUFFICIENT_DATA", articuloId);
            return new DemandPrediction(articuloId, BigDecimal.ZERO, BigDecimal.ZERO,
                    BigDecimal.ZERO, "INSUFFICIENT_DATA");
        }
        List<SerieDiaria> serie = seleccionMetodoService.serieDiaria(articuloId, historialDiasConfigurado);
        if (serie == null || serie.isEmpty()) {
            return new DemandPrediction(articuloId, BigDecimal.ZERO, BigDecimal.ZERO,
                    BigDecimal.ZERO, "INSUFFICIENT_DATA");
        }
        CalculadoraPronostico.Resultado resultado = CalculadoraPronostico.calcular(
                new CalculadoraPronostico.Articulo(articulo.getCodigo(), articulo.getNombre(),
                        "", "", getCurrentStock(articuloId)),
                serie, horizonte,
                seleccionMetodoService.seleccionPara(articuloId, horizonte).orElse(null),
                opcionesCanonicas(), false, true, ordenesCompraAbiertas(articuloId));

        String confianza = confianza(resultado);
        return new DemandPrediction(
                articuloId,
                BigDecimal.valueOf(resultado.demandaTotal()).setScale(2, RoundingMode.HALF_UP),
                BigDecimal.valueOf(resultado.promedioDiarioCalendario())
                        .setScale(2, RoundingMode.HALF_UP),
                BigDecimal.valueOf(tendenciaCalendario(serie)).setScale(2, RoundingMode.HALF_UP),
                confianza,
                resultado.metodo() == null ? null : resultado.metodo().name(),
                resultado.mase(), resultado.sesgo(),
                etiquetas(resultado.flags()),
                resultado.intervaloInferior(), resultado.intervaloSuperior(),
                resultado.intervaloAproximado() ? "APROXIMADO" : "EMPIRICO");
    }

    @Nullable
    private ReorderRecommendation recomendacionCanonica(@Nonnull Long articuloId) {
        Articulos articulo = entityManager.find(Articulos.class, articuloId);
        if (articulo == null) {
            return null;
        }
        int stock = getCurrentStock(articuloId);
        List<SerieDiaria> serie = seleccionMetodoService.serieDiaria(articuloId, historialDiasConfigurado);
        if (serie == null || serie.isEmpty()) {
            return new ReorderRecommendation(articuloId, articulo.getNombre(), stock,
                    0, 0, "NO_SALES_DATA", BigDecimal.ZERO);
        }
        int horizonte = normalizarHorizonte(Math.max(30,
                configuracionPronosticoService.parametros().plazoReposicionDias() * 2));
        CalculadoraPronostico.Resultado resultado = CalculadoraPronostico.calcular(
                new CalculadoraPronostico.Articulo(articulo.getCodigo(), articulo.getNombre(), "", "", stock),
                serie, horizonte,
                seleccionMetodoService.seleccionPara(articuloId, horizonte).orElse(null),
                opcionesCanonicas(), false, true, ordenesCompraAbiertas(articuloId));

        int dias = resultado.diasHastaQuiebre();
        String estado;
        if (stock <= 0) {
            estado = "OUT_OF_STOCK";
        } else if (dias <= 3) {
            estado = "CRITICAL";
        } else if (dias <= 7) {
            estado = "LOW";
        } else if (dias <= 14) {
            estado = "MODERATE";
        } else {
            estado = "OPTIMAL";
        }
        return new ReorderRecommendation(articuloId, articulo.getNombre(), stock,
                resultado.cantidadSugerida(), Math.min(999, dias), estado,
                BigDecimal.valueOf(resultado.promedioDiarioCalendario())
                        .setScale(2, RoundingMode.HALF_UP));
    }

    @Nonnull
    private ReportePronosticos reportePronosticosCanonico(int dias,
                                                          @Nullable String seccion,
                                                          @Nullable String departamento,
                                                          @Nullable String familia) {
        SnapshotCanonico snapshot = snapshotCanonico(dias);
        String depto = normalizarFiltro(departamento);
        String fam = normalizarFiltro(familia);
        boolean vistaSalud = "salud".equals(seccion);

        SortedSet<String> departamentos = new TreeSet<>();
        SortedSet<String> familias = new TreeSet<>();
        for (CalculadoraPronostico.Resultado resultado : snapshot.resultados()) {
            if (!resultado.articulo().departamento().isBlank()) {
                departamentos.add(resultado.articulo().departamento());
            }
            if (!resultado.articulo().familia().isBlank()) {
                familias.add(resultado.articulo().familia());
            }
        }

        List<FilaPronostico> filas = new ArrayList<>();
        if (!vistaSalud) {
            for (CalculadoraPronostico.Resultado resultado : snapshot.resultados()) {
                if (resultado.diasConDemanda() <= 0 || resultado.diario().isEmpty()) {
                    continue;
                }
                if (!coincide(resultado.articulo().departamento(),
                        resultado.articulo().familia(), depto, fam)) {
                    continue;
                }
                filas.add(new FilaPronostico(
                        resultado.articulo().codigo(),
                        resultado.articulo().nombre(),
                        resultado.articulo().departamento(),
                        resultado.articulo().familia(),
                        resultado.articulo().stock(),
                        (int) Math.round(resultado.demandaTotal()),
                        diasRestantesCanonico(resultado),
                        resultado.cantidadSugerida(),
                        resultado.recomendacion(),
                        resultado.metodo() == null ? null : resultado.metodo().name(),
                        resultado.mase(),
                        resultado.sesgo(),
                        etiquetas(resultado.flags())));
            }
        }

        List<FilaCritico> criticos = new ArrayList<>();
        if (vistaSalud) {
            for (CalculadoraPronostico.Resultado resultado : snapshot.resultados()) {
                if (criticos.size() >= CRITICOS_VISIBLES) {
                    break;
                }
                if (esCriticoCanonico(resultado)
                        && coincide(resultado.articulo().departamento(),
                        resultado.articulo().familia(), depto, fam)) {
                    criticos.add(new FilaCritico(resultado.articulo().codigo(),
                            resultado.articulo().nombre(), resultado.articulo().departamento(),
                            resultado.articulo().familia()));
                }
            }
        }

        return new ReportePronosticos(
                List.copyOf(departamentos),
                List.copyOf(familias),
                snapshot.salud(),
                filas,
                criticos);
    }

    @Nonnull
    private static InventoryHealthReport saludCanonica(
            @Nonnull List<CalculadoraPronostico.Resultado> resultados) {
        int optimo = 0;
        int bajo = 0;
        int exceso = 0;
        int sinStock = 0;
        List<String> criticos = new ArrayList<>();
        for (CalculadoraPronostico.Resultado resultado : resultados) {
            int stock = resultado.articulo().stock();
            if (stock <= 0) {
                sinStock++;
                if (criticos.size() < CRITICOS_VISIBLES) {
                    criticos.add(resultado.articulo().nombre());
                }
            } else if (resultado.diasConDemanda() <= 0) {
                optimo++;
            } else {
                double reorden = resultado.puntoReorden();
                if (stock < reorden) {
                    bajo++;
                    if (stock < reorden / 2.0 && criticos.size() < CRITICOS_VISIBLES) {
                        criticos.add(resultado.articulo().nombre());
                    }
                } else if (stock > resultado.stockOptimo() * 2) {
                    exceso++;
                } else {
                    optimo++;
                }
            }
        }
        return new InventoryHealthReport(
                resultados.size(), optimo, bajo, exceso, sinStock, criticos);
    }

    private static boolean esCriticoCanonico(@Nonnull CalculadoraPronostico.Resultado resultado) {
        int stock = resultado.articulo().stock();
        if (stock <= 0) {
            return true;
        }
        if (resultado.diasConDemanda() <= 0) {
            return false;
        }
        return stock < resultado.puntoReorden() / 2.0;
    }

    @Nonnull
    private static String diasRestantesCanonico(@Nonnull CalculadoraPronostico.Resultado resultado) {
        if (resultado.promedioDiarioCalendario() <= 0.0) {
            return "-";
        }
        int dias = resultado.diasHastaQuiebre();
        if (dias > resultado.horizonte()) {
            dias = (int) Math.floor(resultado.articulo().stock()
                    / resultado.promedioDiarioCalendario());
        }
        return String.valueOf(Math.max(0, Math.min(999, dias)));
    }

    @Nonnull
    private static String confianza(@Nonnull CalculadoraPronostico.Resultado resultado) {
        if (resultado.mase() == null || !Double.isFinite(resultado.mase())) {
            return "UNMEASURED";
        }
        if (resultado.mase() <= 0.80) {
            return "HIGH";
        }
        if (resultado.mase() <= 1.00) {
            return "MEDIUM";
        }
        return "LOW";
    }

    /** Tendencia de la serie cero-rellenada: segunda mitad / primera mitad, acotada. */
    private static double tendenciaCalendario(@Nonnull List<SerieDiaria> serie) {
        if (serie.size() < 7) {
            return 1.0;
        }
        int mitad = serie.size() / 2;
        double primera = 0.0;
        double segunda = 0.0;
        for (int i = 0; i < mitad; i++) {
            primera += serie.get(i).cantidad();
        }
        for (int i = mitad; i < serie.size(); i++) {
            segunda += serie.get(i).cantidad();
        }
        double diasPrimera = mitad;
        double diasSegunda = serie.size() - mitad;
        if (diasPrimera <= 0 || primera <= 0.0) {
            return 1.0;
        }
        double factor = (segunda / diasSegunda) / (primera / diasPrimera);
        return Math.max(0.5, Math.min(2.0, factor));
    }

    @Nonnull
    private static String etiquetas(@Nonnull java.util.Set<FlagCalidad> flags) {
        if (flags.isEmpty()) {
            return "";
        }
        return flags.stream().map(FlagCalidad::name).sorted().reduce((a, b) -> a + "," + b).orElse("");
    }

    private static String metodoFrecuente(@Nonnull List<CalculadoraPronostico.Resultado> resultados) {
        Map<String, Integer> conteo = new HashMap<>();
        for (CalculadoraPronostico.Resultado resultado : resultados) {
            if (resultado.diasConDemanda() > 0 && resultado.metodo() != null) {
                conteo.merge(resultado.metodo().name(), 1, Integer::sum);
            }
        }
        return conteo.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse("-");
    }

    private static int normalizarHorizonte(int dias) {
        if (dias <= 0) {
            return 30;
        }
        return Math.min(90, dias);
    }

    /**
     * El filtro por departamento/familia (vacío = todos).
     */
    private static boolean coincide(@Nonnull String departamentoArticulo,
                                    @Nonnull String familiaArticulo,
                                    @Nonnull String departamento,
                                    @Nonnull String familia) {
        if (!departamento.isEmpty()
                && !departamento.equalsIgnoreCase(departamentoArticulo.trim())) {
            return false;
        }
        return familia.isEmpty() || familia.equalsIgnoreCase(familiaArticulo.trim());
    }

    @Nonnull
    private static String normalizarFiltro(@Nullable String valor) {
        return valor == null ? "" : valor.trim();
    }

    private static long numeroLargo(@Nullable Object valor) {
        return valor instanceof Number numero ? numero.longValue() : 0L;
    }

    private static int numeroEntero(@Nullable Object valor) {
        return valor instanceof Number numero ? numero.intValue() : 0;
    }

    @Nonnull
    private static BigDecimal decimal(@Nullable Object valor) {
        if (valor instanceof BigDecimal exacto) {
            return exacto;
        }
        return valor instanceof Number numero
                ? BigDecimal.valueOf(numero.doubleValue()) : BigDecimal.ZERO;
    }

    @Nonnull
    private static String texto(@Nullable Object valor) {
        return valor == null ? "" : String.valueOf(valor);
    }

    /**
     * Historial heredado por nombre de articulo. Solo se usa con
     * {@code mercurius.pronostico.canonico-habilitado=false}; el camino de
     * produccion lee la rejilla canonica por clave en
     * {@link SeleccionMetodoService#seriesDiariasActivas(int)}.
     *
     * <p>Se conserva durante un ciclo de evaluacion para poder revertir sin
     * recompilar. No debe usarse en codigo nuevo: dos articulos con el mismo
     * nombre comparten este historial.</p>
     */
    @Deprecated
    @CacheResult(cacheName = "analytics-forecast")
    List<SalesData> getSalesHistory(Long articuloId, int days) {
        LocalDateTime startDate = LocalDate.now().minusDays(days).atStartOfDay();
        LocalDateTime endDate = LocalDate.now().atTime(23, 59, 59);

        List<Object[]> results = entityManager.createQuery(
            "SELECT CAST(e.fechaEmision AS date), COALESCE(SUM(ld.cantidad), 0) " +
            "FROM ComprobantesEmitidos f " +
            "JOIN f.detalles d " +
            "JOIN d.lineasDetalle ld " +
            "JOIN f.encabezado e " +
            "WHERE f.status = true " +
            "AND e.fechaEmision BETWEEN :start AND :end " +
            "AND ld.detalle = (SELECT a.nombre FROM Articulos a WHERE a.codigo = :articuloId) " +
            "GROUP BY CAST(e.fechaEmision AS date) " +
            "ORDER BY CAST(e.fechaEmision AS date)",
            Object[].class
        )
        .setParameter("start", startDate)
        .setParameter("end", endDate)
        .setParameter("articuloId", articuloId)
        .getResultList();

        return results.stream()
            .map(row -> {
                Object dateObj = row[0];
                LocalDate date;
                if (dateObj instanceof LocalDate) {
                    date = (LocalDate) dateObj;
                } else if (dateObj instanceof java.sql.Date) {
                    date = ((java.sql.Date) dateObj).toLocalDate();
                } else if (dateObj instanceof java.util.Date) {
                    date = ((java.util.Date) dateObj).toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
                } else {
                    date = LocalDate.parse(dateObj.toString());
                }
                return new SalesData(date, ((Number) row[1]).doubleValue());
            })
            .collect(Collectors.toList());
    }

    private int getCurrentStock(Long articuloId) {
        try {
            List<ArticuloStock> filas = entityManager.createQuery(
                "SELECT s FROM ArticuloStock s "
                    + "WHERE s.articuloCodigo = :articuloId "
                    + "OR (s.articuloCodigo IS NULL AND s.codigoBarra = "
                    + "(SELECT a.codigoBarra FROM Articulos a WHERE a.codigo = :articuloId))",
                ArticuloStock.class
            ).setParameter("articuloId", articuloId)
             .getResultList();
            if (filas.isEmpty()) {
                return 0;
            }
            // Si conviven la fila nueva (por articulo) y una legacy (por
            // barcode), manda la de identidad estable. La reconciliacion
            // reporta la diferencia en vez de sumar dos veces.
            ArticuloStock elegida = filas.stream()
                    .filter(fila -> fila.getArticuloCodigo() != null)
                    .findFirst()
                    .orElse(filas.get(0));
            return elegida.getStock() == null ? 0 : elegida.getStock().intValue();
        } catch (jakarta.persistence.PersistenceException e) {
            LOG.warn("getCurrentStock: no se pudo leer el stock del articulo "
                    + articuloId + "; no se convierte el fallo en cero", e);
            throw e;
        }
    }

    private BigDecimal calculateAverageDailySales(List<SalesData> salesHistory) {
        if (salesHistory.isEmpty()) return BigDecimal.ZERO;
        
        BigDecimal total = salesHistory.stream()
            .map(SalesData::quantity)
            .map(BigDecimal::valueOf)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        
        return total.divide(BigDecimal.valueOf(salesHistory.size()), 4, RoundingMode.HALF_UP);
    }

    private BigDecimal calculateSalesVelocity(List<SalesData> salesHistory) {
        if (salesHistory.size() < 2) return BigDecimal.ONE;
        
        double sum = 0;
        double sumSquared = 0;
        
        for (SalesData data : salesHistory) {
            sum += data.quantity();
            sumSquared += data.quantity() * data.quantity();
        }
        
        double n = salesHistory.size();
        double mean = sum / n;
        double variance = (sumSquared / n) - (mean * mean);
        double stdDev = Math.sqrt(variance);
        
        BigDecimal coefficientOfVariation = BigDecimal.valueOf(stdDev / mean);
        
        return BigDecimal.ONE.add(coefficientOfVariation.negate());
    }

    private BigDecimal calculateTrendFactor(List<SalesData> salesHistory) {
        if (salesHistory.size() < 7) return BigDecimal.ONE;
        
        int half = salesHistory.size() / 2;
        List<SalesData> firstHalf = salesHistory.subList(0, half);
        List<SalesData> secondHalf = salesHistory.subList(half, salesHistory.size());
        
        BigDecimal firstAvg = calculateAverageDailySales(firstHalf);
        BigDecimal secondAvg = calculateAverageDailySales(secondHalf);
        
        if (firstAvg.compareTo(BigDecimal.ZERO) == 0) return BigDecimal.ONE;
        
        return secondAvg.divide(firstAvg, 4, RoundingMode.HALF_UP);
    }

    private BigDecimal calculateSeasonalFactor(List<SalesData> salesHistory) {
        if (salesHistory.isEmpty()) return BigDecimal.ONE;
        
        Map<DayOfWeek, List<Double>> dayOfWeekSales = new HashMap<>();
        
        for (SalesData data : salesHistory) {
            DayOfWeek day = data.date().getDayOfWeek();
            dayOfWeekSales.computeIfAbsent(day, k -> new ArrayList<>()).add(data.quantity());
        }
        
        BigDecimal currentDayFactor = BigDecimal.ONE;
        DayOfWeek today = LocalDate.now().getDayOfWeek();
        
        if (dayOfWeekSales.containsKey(today)) {
            List<Double> todaySales = dayOfWeekSales.get(today);
            double avgToday = todaySales.stream().mapToDouble(Double::doubleValue).average().orElse(0);
            double overallAvg = salesHistory.stream().mapToDouble(SalesData::quantity).average().orElse(0);
            
            if (overallAvg > 0) {
                currentDayFactor = BigDecimal.valueOf(avgToday / overallAvg);
            }
        }
        
        return currentDayFactor;
    }

    private int calculateReorderPoint(BigDecimal avgDailySales, int leadTimeDays) {
        return avgDailySales.setScale(0, RoundingMode.HALF_UP)
            .multiply(BigDecimal.valueOf(leadTimeDays))
            .intValue() + 1;
    }

    private int calculateOptimalStock(BigDecimal avgDailySales, int daysStock) {
        return avgDailySales.setScale(0, RoundingMode.HALF_UP)
            .multiply(BigDecimal.valueOf(daysStock))
            .intValue();
    }

    private int calculateSafetyStock(List<SalesData> salesHistory) {
        if (salesHistory.size() < 7) return 7;
        
        double sum = salesHistory.stream().mapToDouble(SalesData::quantity).sum();
        double mean = sum / salesHistory.size();
        
        double variance = salesHistory.stream()
            .mapToDouble(d -> Math.pow(d.quantity() - mean, 2))
            .sum() / salesHistory.size();
        
        double stdDev = Math.sqrt(variance);
        
        return Math.max(1, (int) Math.ceil(stdDev * 1.65));
    }

    public record SalesData(LocalDate date, double quantity) {}

    /**
     * Pronóstico de un artículo.
     *
     * <p>Los getters no siguen la convención de JavaBean ({@code getX()}) sino
     * el estilo record ({@code x()}), así que Jackson no encuentra
     * serializadores: el endpoint /api/stock-forecast/bulk-forecast devolvía
     * 500 y la gráfica de "Demanda Pronosticada" nunca se dibujaba. Con
     * {@code @JsonProperty} el JSON sale con los mismos nombres que el
     * JavaScript ya esperaba ({@code articuloNombre}, {@code predictedSales},
     * ...).</p>
     */
    public static class ProductForecast {
        private Long articuloId;
        private String articuloNombre;
        private LocalDate forecastDate;
        private int currentStock;
        private int predictedSales;
        private int predictedStock;
        private boolean shouldReorder;
        private BigDecimal avgDailySales;
        private BigDecimal salesVelocity;
        private String metodo;
        private Double mase;
        private Double sesgo;
        private String calidadFlags;
        private double predictedDemand;
        private double reorderPoint;
        private int recommendedReorder;

        public ProductForecast(Long articuloId, String articuloNombre, LocalDate forecastDate,
                            int currentStock, int predictedSales, int predictedStock,
                            boolean shouldReorder, BigDecimal avgDailySales, BigDecimal salesVelocity) {
            this(articuloId, articuloNombre, forecastDate, currentStock, predictedSales,
                    predictedStock, shouldReorder, avgDailySales, salesVelocity,
                    null, null, null, "", predictedSales, 0, 0);
        }

        public ProductForecast(Long articuloId, String articuloNombre, LocalDate forecastDate,
                            int currentStock, int predictedSales, int predictedStock,
                            boolean shouldReorder, BigDecimal avgDailySales, BigDecimal salesVelocity,
                            String metodo, Double mase, Double sesgo, String calidadFlags,
                            double predictedDemand, double reorderPoint, int recommendedReorder) {
            this.articuloId = articuloId;
            this.articuloNombre = articuloNombre;
            this.forecastDate = forecastDate;
            this.currentStock = currentStock;
            this.predictedSales = predictedSales;
            this.predictedStock = predictedStock;
            this.shouldReorder = shouldReorder;
            this.avgDailySales = avgDailySales;
            this.salesVelocity = salesVelocity;
            this.metodo = metodo;
            this.mase = mase;
            this.sesgo = sesgo;
            this.calidadFlags = calidadFlags;
            this.predictedDemand = predictedDemand;
            this.reorderPoint = reorderPoint;
            this.recommendedReorder = recommendedReorder;
        }

        @JsonProperty("articuloId")
        public Long articuloId() { return articuloId; }
        @JsonProperty("articuloNombre")
        public String articuloNombre() { return articuloNombre; }
        @JsonProperty("forecastDate")
        public LocalDate forecastDate() { return forecastDate; }
        @JsonProperty("currentStock")
        public int currentStock() { return currentStock; }
        @JsonProperty("predictedSales")
        public int predictedSales() { return predictedSales; }
        @JsonProperty("predictedStock")
        public int predictedStock() { return predictedStock; }
        @JsonProperty("shouldReorder")
        public boolean shouldReorder() { return shouldReorder; }
        @JsonProperty("avgDailySales")
        public BigDecimal avgDailySales() { return avgDailySales; }
        @JsonProperty("salesVelocity")
        public BigDecimal salesVelocity() { return salesVelocity; }
        @JsonProperty("metodo")
        public String metodo() { return metodo; }
        @JsonProperty("mase")
        public Double mase() { return mase; }
        @JsonProperty("sesgo")
        public Double sesgo() { return sesgo; }
        @JsonProperty("calidadFlags")
        public String calidadFlags() { return calidadFlags; }
        /** Demanda total del horizonte; campo nuevo, `predictedSales` sigue siendo la del dia. */
        @JsonProperty("predictedDemand")
        public double predictedDemand() { return predictedDemand; }
        @JsonProperty("reorderPoint")
        public double reorderPoint() { return reorderPoint; }
        @JsonProperty("recommendedReorder")
        public int recommendedReorder() { return recommendedReorder; }
    }

    public static class DemandPrediction {
        private Long articuloId;
        private BigDecimal predictedDemand;
        private BigDecimal avgDailySales;
        private BigDecimal trendFactor;
        private String confidence;
        private String metodo;
        private Double mase;
        private Double sesgo;
        private String calidadFlags;
        private double lowerBound;
        private double upperBound;
        private String intervalType;

        public DemandPrediction(Long articuloId, BigDecimal predictedDemand, BigDecimal avgDailySales,
                             BigDecimal trendFactor, String confidence) {
            this(articuloId, predictedDemand, avgDailySales, trendFactor, confidence,
                    null, null, null, "", 0.0, 0.0, "APROXIMADO");
        }

        public DemandPrediction(Long articuloId, BigDecimal predictedDemand, BigDecimal avgDailySales,
                             BigDecimal trendFactor, String confidence, String metodo,
                             Double mase, Double sesgo, String calidadFlags,
                             double lowerBound, double upperBound, String intervalType) {
            this.articuloId = articuloId;
            this.predictedDemand = predictedDemand;
            this.avgDailySales = avgDailySales;
            this.trendFactor = trendFactor;
            this.confidence = confidence;
            this.metodo = metodo;
            this.mase = mase;
            this.sesgo = sesgo;
            this.calidadFlags = calidadFlags;
            this.lowerBound = lowerBound;
            this.upperBound = upperBound;
            this.intervalType = intervalType;
        }

        public Long articuloId() { return articuloId; }
        public BigDecimal predictedDemand() { return predictedDemand; }
        public BigDecimal avgDailySales() { return avgDailySales; }
        public BigDecimal trendFactor() { return trendFactor; }
        public String confidence() { return confidence; }
        public String metodo() { return metodo; }
        public Double mase() { return mase; }
        public Double sesgo() { return sesgo; }
        public String calidadFlags() { return calidadFlags; }
        public double lowerBound() { return lowerBound; }
        public double upperBound() { return upperBound; }
        public String intervalType() { return intervalType; }
    }

    public static class InventoryHealthReport {
        private int totalProducts;
        private int optimal;
        private int lowStock;
        private int overStocked;
        private int outOfStock;
        private List<String> criticalItems;

        public InventoryHealthReport(int totalProducts, int optimal, int lowStock, int overStocked,
                                   int outOfStock, List<String> criticalItems) {
            this.totalProducts = totalProducts;
            this.optimal = optimal;
            this.lowStock = lowStock;
            this.overStocked = overStocked;
            this.outOfStock = outOfStock;
            this.criticalItems = criticalItems;
        }

        public int totalProducts() { return totalProducts; }
        public int optimal() { return optimal; }
        public int lowStock() { return lowStock; }
        public int overStocked() { return overStocked; }
        public int outOfStock() { return outOfStock; }
        public List<String> criticalItems() { return criticalItems; }
    }

    public static class ReorderRecommendation {
        private Long articuloId;
        private String articuloNombre;
        private int currentStock;
        private int recommendedReorder;
        private int daysUntilStockout;
        private String status;
        private BigDecimal avgDailySales;

        public ReorderRecommendation(Long articuloId, String articuloNombre, int currentStock,
                                   int recommendedReorder, int daysUntilStockout, String status,
                                   BigDecimal avgDailySales) {
            this.articuloId = articuloId;
            this.articuloNombre = articuloNombre;
            this.currentStock = currentStock;
            this.recommendedReorder = recommendedReorder;
            this.daysUntilStockout = daysUntilStockout;
            this.status = status;
            this.avgDailySales = avgDailySales;
        }

        public Long articuloId() { return articuloId; }
        public String articuloNombre() { return articuloNombre; }
        public int currentStock() { return currentStock; }
        public int recommendedReorder() { return recommendedReorder; }
        public int daysUntilStockout() { return daysUntilStockout; }
        public String status() { return status; }
        public BigDecimal avgDailySales() { return avgDailySales; }
    }
}
