package Services;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import Models.Articulos.Articulos;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jakarta.transaction.Transactional.TxType;
import io.quarkus.cache.CacheResult;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Pronostico de stock por articulo.
 *
 * <p>Igual que {@link ProductPerformanceService}, {@code getSalesHistory} agrega
 * con {@code COALESCE(SUM(ld.cantidad), 0)}: los comprobantes REP (tipo 10) no
 * llevan Cantidad en su LineaDetalle, de modo que un dia con solo lineas REP
 * agrupaba a NULL y el mapeo lanzaba NullPointerException en vez de reportar 0
 * unidades vendidas.
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

    @Inject @Nonnull
    EntityManager entityManager;

    @Transactional(TxType.SUPPORTS)
    @Nonnull
    public List<ProductForecast> generateForecast(@Nonnull Long articuloId, int forecastDays) {
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
        return saludDe(estadisticasCatalogo());
    }

    @Transactional(TxType.SUPPORTS)
    @Nullable
    public ReorderRecommendation getReorderRecommendation(@Nonnull Long articuloId) {
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
     */
    @Transactional(TxType.SUPPORTS)
    @Nonnull
    public List<EstadisticaArticulo> estadisticasCatalogo() {
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
                        proyeccion.stockPronosticado() <= 0 ? "Comprar" : "Pronto"));
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

    /** Agregados de venta y stock de un artículo, listos para pronosticar. */
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
            @Nonnull String recomendacion) {
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

    /** El filtro por departamento/familia (vacío = todos). */
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
            BigDecimal stock = entityManager.createQuery(
                "SELECT COALESCE(SUM(i.cantidad), 0) FROM Inventario i WHERE i.articulo.codigo = :articuloId",
                BigDecimal.class
            ).setParameter("articuloId", articuloId)
             .getSingleResult();
            return stock != null ? stock.intValue() : 0;
        } catch (jakarta.persistence.PersistenceException e) {
            return 0;
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

        public ProductForecast(Long articuloId, String articuloNombre, LocalDate forecastDate,
                            int currentStock, int predictedSales, int predictedStock,
                            boolean shouldReorder, BigDecimal avgDailySales, BigDecimal salesVelocity) {
            this.articuloId = articuloId;
            this.articuloNombre = articuloNombre;
            this.forecastDate = forecastDate;
            this.currentStock = currentStock;
            this.predictedSales = predictedSales;
            this.predictedStock = predictedStock;
            this.shouldReorder = shouldReorder;
            this.avgDailySales = avgDailySales;
            this.salesVelocity = salesVelocity;
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
    }

    public static class DemandPrediction {
        private Long articuloId;
        private BigDecimal predictedDemand;
        private BigDecimal avgDailySales;
        private BigDecimal trendFactor;
        private String confidence;

        public DemandPrediction(Long articuloId, BigDecimal predictedDemand, BigDecimal avgDailySales,
                             BigDecimal trendFactor, String confidence) {
            this.articuloId = articuloId;
            this.predictedDemand = predictedDemand;
            this.avgDailySales = avgDailySales;
            this.trendFactor = trendFactor;
            this.confidence = confidence;
        }

        public Long articuloId() { return articuloId; }
        public BigDecimal predictedDemand() { return predictedDemand; }
        public BigDecimal avgDailySales() { return avgDailySales; }
        public BigDecimal trendFactor() { return trendFactor; }
        public String confidence() { return confidence; }
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
