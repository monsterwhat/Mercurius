package Controllers.Api.App.Reportes;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import Models.PrecisionPronostico;
import Services.SeleccionMetodoService;
import Services.StockForecastService;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

/**
 * Pronósticos de Inventario for the NEW app surface — port of
 * {@code secured/pages/Inventario/Reportes/Pronosticos/index.xhtml}
 * (plan task T19, read-only).
 *
 * <p>Unlike the legacy {@code StockForecastUIBean} (hardcoded sample rows),
 * this resource is backed DIRECTLY by the existing
 * {@link StockForecastService}: {@link StockForecastService#generateBulkForecast(int)}
 * for the demand forecast section and
 * {@link StockForecastService#getInventoryHealthReport()} for the health
 * section — no HTTP self-calls. The legacy three-tab layout becomes a
 * {@code seccion} query param (pronosticos|salud|reorden) driving one
 * server-rendered kit table; the {@code dias} filter maps to the legacy
 * "Días de Pronóstico" select.</p>
 *
 * <p>The page embeds a &lt;canvas&gt; fed by an inline script pulling JSON from
 * the EXISTING {@code /api/stock-forecast/bulk-forecast} endpoint with
 * {@code credentials=same-origin}.</p>
 *
 * <p><b>Exactitud del pronóstico.</b> Cada fila por artículo de las secciones
 * {@code pronosticos} y {@code reorden} añade tres columnas de solo lectura —
 * método elegido, MASE y sesgo — leídas de {@link PrecisionPronostico} (la
 * última corrida de backtesting) y de
 * {@link SeleccionMetodoService#metodoElegido(Long)}, que es la única fuente
 * de verdad de la elección. Un artículo sin filas de precisión se muestra
 * como "sin evaluar", nunca como celda vacía ni como error: una columna de
 * exactitud no puede ser la razón por la que la página no carga.</p>
 */
@Path("/app/reportes/inventario/pronosticos")
@Produces(MediaType.TEXT_HTML)
@RolesAllowed({"admin", "inventario"})
public class PronosticosResource {

    private static final Logger LOG = Logger.getLogger(PronosticosResource.class);

    private static final String BASE_URL = "/app/reportes/inventario/pronosticos";

    /** Marcador de un artículo sin filas de precisión (nunca celda vacía). */
    private static final String SIN_EVALUAR = "sin evaluar";

    /** Sustituto de una métrica indefinida (MASE o sesgo nulos). */
    private static final String SIN_METRICA = "-";

    @Inject
    @Nonnull
    StockForecastService stockForecastService;

    @Inject
    @Nonnull
    SeleccionMetodoService seleccionMetodoService;

    @Inject
    @Nonnull
    EntityManager entityManager;

    @Inject
    @Location("pages/reportes/pronosticos")
    Template pagina;

    @Inject
    @Location("pages/reportes/_tablas/pronosticos")
    Template tabla;

    @GET
    @Transactional
    public Response get(
            @Context @Nonnull HttpHeaders headers,
            @QueryParam("page") @DefaultValue("1") int page,
            @QueryParam("size") @DefaultValue("10") int size,
            @QueryParam("sort") @Nullable String sort,
            @QueryParam("dir") @DefaultValue("asc") String dir,
            @QueryParam("dias") @DefaultValue("30") String dias,
            @QueryParam("seccion") @Nullable String seccion) {

        String vista = normalizarSeccion(seccion);
        int diasPronostico = normalizarDias(dias);
        Map<String, String> filtros = new LinkedHashMap<>();
        filtros.put("dias", String.valueOf(diasPronostico));
        filtros.put("seccion", vista);

        List<Map<String, Object>> filas = new ArrayList<>();
        if ("pronosticos".equals(vista)) {
            List<StockForecastService.ProductForecast> pronosticos =
                    stockForecastService.generateBulkForecast(diasPronostico);
            if (pronosticos != null) {
                Map<Long, Exactitud> exactitudes = exactitudPorArticulo(pronosticos);
                for (StockForecastService.ProductForecast pronostico : pronosticos) {
                    Exactitud acc = exactitud(pronostico, exactitudes);
                    filas.add(Tablas.fila(
                            "articulo", pronostico.articuloNombre(),
                            "stockActual", String.valueOf(pronostico.currentStock()),
                            "demandaEstimada", String.valueOf(pronostico.predictedSales()),
                            "diasRestantes", diasRestantes(pronostico),
                            "recomendacion", recomendacion(pronostico),
                            "metodoElegido", acc.metodo(),
                            "mase", acc.mase(),
                            "sesgo", acc.sesgo()));
                }
            }
        } else if ("salud".equals(vista)) {
            StockForecastService.InventoryHealthReport salud =
                    stockForecastService.getInventoryHealthReport();
            // The service report carries aggregate counters + critical item names.
            // Those rows are NAMES, not articles, so there is no key to join the
            // precision table on: this section stays accuracy-free on purpose.
            if (salud != null && salud.criticalItems() != null) {
                for (String critico : salud.criticalItems()) {
                    filas.add(Tablas.fila(
                            "articulo", critico,
                            "estado", "Cr\u00edtico"));
                }
            }
        } else {
            List<StockForecastService.ProductForecast> pronosticos =
                    stockForecastService.generateBulkForecast(diasPronostico);
            if (pronosticos != null) {
                Map<Long, Exactitud> exactitudes = exactitudPorArticulo(pronosticos);
                for (StockForecastService.ProductForecast pronostico : pronosticos) {
                    Exactitud acc = exactitud(pronostico, exactitudes);
                    int sugerido = Math.max(0,
                            pronostico.avgDailySales().multiply(
                                    BigDecimal.valueOf(diasPronostico))
                                    .setScale(0, java.math.RoundingMode.HALF_UP).intValue()
                            - pronostico.currentStock());
                    filas.add(Tablas.fila(
                            "articulo", pronostico.articuloNombre(),
                            "stockActual", String.valueOf(pronostico.currentStock()),
                            "cantidadSugerida", String.valueOf(sugerido),
                            "prioridad", recomendacion(pronostico),
                            "metodoElegido", acc.metodo(),
                            "mase", acc.mase(),
                            "sesgo", acc.sesgo()));
                }
            }
        }

        Tablas.ordenar(filas, sort, dir);
        long totalFilas = filas.size();
        int totalPages = Tablas.totalPaginas(totalFilas, size);

        StockForecastService.InventoryHealthReport salud =
                stockForecastService.getInventoryHealthReport();

        Map<String, Object> model = new LinkedHashMap<>();
        model.put("titulo", "Pron\u00f3sticos de Inventario");
        model.put("baseUrl", BASE_URL);
        model.put("columnas", columnas(vista));
        model.put("filas", Tablas.paginaDe(filas, page, size));
        model.put("sortKey", sort);
        model.put("sortDir", dir);
        model.put("page", Math.max(page, 1));
        model.put("size", size);
        model.put("total", totalFilas);
        model.put("totalPages", totalPages);
        model.put("pages", Tablas.ventanaPaginas(page, totalPages));
        model.put("params", Tablas.params(filtros));
        model.put("filtros", filtros);
        model.put("seccion", vista);
        model.put("dias", diasPronostico);
        model.put("totalProductos", salud != null ? salud.totalProducts() : 0);
        model.put("saludables", salud != null ? salud.optimal() : 0);
        model.put("stockBajo", salud != null ? salud.lowStock() : 0);
        model.put("sinStock", salud != null ? salud.outOfStock() : 0);

        boolean fragmento = headers.getHeaderString("HX-Request") != null;
        Template plantilla = fragmento ? tabla : pagina;
        String html = plantilla.data(model).render();
        return Response.ok(html)
                .type(MediaType.TEXT_HTML_TYPE.withCharset("UTF-8"))
                .build();
    }

    @Nonnull
    private static String normalizarSeccion(@Nullable String seccion) {
        if (seccion == null) {
            return "pronosticos";
        }
        return switch (seccion) {
            case "salud", "reorden" -> seccion;
            default -> "pronosticos";
        };
    }

    private static int normalizarDias(@Nullable String dias) {
        try {
            int valor = Integer.parseInt(dias == null ? "30" : dias.trim());
            if (valor == 7 || valor == 14 || valor == 30 || valor == 60) {
                return valor;
            }
        } catch (NumberFormatException e) {
            // fall through to default
        }
        return 30;
    }

    /** Legacy tag semantics: Comprar (danger) / Pronto (warning) / OK (success). */
    @Nonnull
    private static String recomendacion(@Nonnull StockForecastService.ProductForecast p) {
        if (p.shouldReorder() && p.predictedStock() <= 0) {
            return "Comprar";
        }
        if (p.shouldReorder()) {
            return "Pronto";
        }
        return "OK";
    }

    /** Days of cover left at the average daily sales rate. */
    @Nonnull
    private static String diasRestantes(@Nonnull StockForecastService.ProductForecast p) {
        BigDecimal promedio = p.avgDailySales();
        if (promedio == null || promedio.compareTo(BigDecimal.ZERO) <= 0) {
            return "-";
        }
        int dias = BigDecimal.valueOf(p.currentStock())
                .divide(promedio, 0, java.math.RoundingMode.DOWN).intValue();
        return String.valueOf(Math.max(0, dias));
    }

    @Nonnull
    private static List<Map<String, Object>> columnas(@Nonnull String vista) {
        if ("salud".equals(vista)) {
            return List.of(
                    Map.of("label", "Artículo", "key", "articulo"),
                    Map.of("label", "Estado", "key", "estado"));
        }
        if ("reorden".equals(vista)) {
            List<Map<String, Object>> columnas = new ArrayList<>(List.of(
                    Map.of("label", "Artículo", "key", "articulo"),
                    Map.of("label", "Stock Actual", "key", "stockActual"),
                    Map.of("label", "Cantidad Sugerida", "key", "cantidadSugerida"),
                    Map.of("label", "Prioridad", "key", "prioridad")));
            columnas.addAll(columnasExactitud());
            return List.copyOf(columnas);
        }
        List<Map<String, Object>> columnas = new ArrayList<>(List.of(
                Map.of("label", "Artículo", "key", "articulo"),
                Map.of("label", "Stock Actual", "key", "stockActual"),
                Map.of("label", "Demanda Pronosticada", "key", "demandaEstimada"),
                Map.of("label", "Días Restantes", "key", "diasRestantes"),
                Map.of("label", "Recomendación", "key", "recomendacion")));
        columnas.addAll(columnasExactitud());
        return List.copyOf(columnas);
    }

    /**
     * Columnas de exactitud del pronóstico.
     *
     * <p>Van SIN {@code key}: el kit trata una columna sin clave como no
     * ordenable, y es lo honesto aquí — las celdas son texto ya formateado
     * ("Media móvil", "0.83", "0.42 · calibrado") y ordenar alfabéticamente un
     * número embebido en texto daría un orden sin sentido.</p>
     */
    @Nonnull
    private static List<Map<String, Object>> columnasExactitud() {
        return List.of(
                Map.of("label", "Método"),
                Map.of("label", "MASE"),
                Map.of("label", "Sesgo"));
    }

    // ------------------------------------------------------------------
    // Exactitud del pronóstico (Models.PrecisionPronostico)
    // ------------------------------------------------------------------

    /**
     * Tripleta de exactitud de un artículo, ya formateada para la tabla.
     *
     * <p>El único caso "vacío" es {@link #SIN_EVALUAR}: artículo sin filas de
     * precisión, o con filas cuyo MASE es indefinido (serie plana), donde la
     * elección no existe y no hay nada honesto que mostrar.</p>
     */
    private record Exactitud(@Nonnull String metodo,
                             @Nonnull String mase,
                             @Nonnull String sesgo) {

        private static final Exactitud SIN_EVALUAR =
                new Exactitud(PronosticosResource.SIN_EVALUAR,
                        PronosticosResource.SIN_METRICA,
                        PronosticosResource.SIN_METRICA);
    }

    /** Exactitud de un pronóstico, o el marcador "sin evaluar" si no hay datos. */
    @Nonnull
    private static Exactitud exactitud(
            @Nonnull StockForecastService.ProductForecast pronostico,
            @Nonnull Map<Long, Exactitud> porArticulo) {
        if (pronostico.articuloId() == null) {
            return Exactitud.SIN_EVALUAR;
        }
        return porArticulo.getOrDefault(pronostico.articuloId(), Exactitud.SIN_EVALUAR);
    }

    /**
     * Lee la precisión de todos los artículos de la vista en UNA consulta y
     * resuelve el método elegido de cada uno ya evaluado.
     *
     * <p>La elección se delega a {@link SeleccionMetodoService#metodoElegido}:
     * el desempate por simplicidad es regla del servicio y no se reimplementa
     * aquí. Solo se pregunta por los artículos que tienen filas, de modo que el
     * número de consultas no crece con el tamaño del catálogo.</p>
     *
     * <p>Si la lectura falla se registra y se degrada a "sin evaluar" para toda
     * la vista: la exactitud es información añadida y no puede ser el motivo de
     * que la página devuelva 500.</p>
     */
    @Nonnull
    private Map<Long, Exactitud> exactitudPorArticulo(
            @Nullable List<StockForecastService.ProductForecast> pronosticos) {
        if (pronosticos == null || pronosticos.isEmpty()) {
            return Map.of();
        }
        Set<Long> codigos = new LinkedHashSet<>();
        for (StockForecastService.ProductForecast pronostico : pronosticos) {
            if (pronostico.articuloId() != null) {
                codigos.add(pronostico.articuloId());
            }
        }
        if (codigos.isEmpty()) {
            return Map.of();
        }

        List<PrecisionPronostico> filas;
        try {
            filas = entityManager.createQuery(
                            "SELECT p FROM PrecisionPronostico p "
                                    + "WHERE p.articulo.codigo IN :codigos",
                            PrecisionPronostico.class)
                    .setParameter("codigos", codigos)
                    .getResultList();
        } catch (RuntimeException e) {
            LOG.warn("exactitudPorArticulo: no se pudieron leer las filas de precision de "
                    + codigos.size() + " articulo(s) (" + e.getMessage()
                    + "); la vista se mostrara como 'sin evaluar'", e);
            return Map.of();
        }

        Map<Long, List<PrecisionPronostico>> porArticulo = new HashMap<>();
        for (PrecisionPronostico fila : filas) {
            if (fila.getArticulo() != null && fila.getArticulo().getCodigo() != null) {
                porArticulo.computeIfAbsent(fila.getArticulo().getCodigo(),
                        clave -> new ArrayList<>()).add(fila);
            }
        }

        Map<Long, Exactitud> resultado = new HashMap<>();
        for (Map.Entry<Long, List<PrecisionPronostico>> articulo : porArticulo.entrySet()) {
            Optional<String> elegido = metodoElegido(articulo.getKey());
            if (elegido.isEmpty()) {
                continue;
            }
            PrecisionPronostico fila = buscar(elegido.get(), articulo.getValue());
            if (fila == null) {
                // La eleccion y las filas se leyeron en la misma transaccion, asi que
                // solo puede pasar si la corrida se modifico en medio: se degrada a
                // "sin evaluar" en vez de fallar la fila entera.
                LOG.warn("exactitudPorArticulo: el articulo " + articulo.getKey()
                        + " quedo elegido el metodo " + elegido.get()
                        + " sin fila de precision correspondiente; se mostrara 'sin evaluar'");
                continue;
            }
            resultado.put(articulo.getKey(), new Exactitud(
                    nombreMetodo(elegido.get()),
                    dosDecimales(fila.getMase()),
                    sesgoLegible(fila.getSesgo())));
        }
        return resultado;
    }

    /** {@code metodoElegido} envuelto: su fallo deja la fila en "sin evaluar". */
    @Nonnull
    private Optional<String> metodoElegido(@Nonnull Long codigoArticulo) {
        try {
            return seleccionMetodoService.metodoElegido(codigoArticulo);
        } catch (RuntimeException e) {
            LOG.warn("metodoElegido: la consulta del metodo elegido del articulo "
                    + codigoArticulo + " fallo (" + e.getMessage()
                    + "); se mostrara 'sin evaluar'", e);
            return Optional.empty();
        }
    }

    @Nonnull
    private static PrecisionPronostico buscar(
            @Nonnull String metodo, @Nonnull List<PrecisionPronostico> filas) {
        for (PrecisionPronostico fila : filas) {
            if (metodo.equals(fila.getMetodo())) {
                return fila;
            }
        }
        return null;
    }

    /**
     * Nombre en español de un método de
     * {@code Services.pronostico.MetodoPronostico}. Un constante desconocido
     * (un método nuevo añadido sin tocar esta página) se muestra tal cual, que
     * es más honesto que ocultarlo tras un marcador.
     */
    @Nonnull
    private static String nombreMetodo(@Nullable String metodo) {
        if (metodo == null || metodo.isBlank()) {
            return SIN_EVALUAR;
        }
        return switch (metodo) {
            case "INGENUO" -> "Ingenuo";
            case "ESTACIONAL_INGENUO" -> "Estacional ingenuo";
            case "MEDIA_MOVIL" -> "Media móvil";
            case "SES" -> "Suavizado exponencial";
            case "HOLT_AMORTIGUADO" -> "Holt amortiguado";
            case "HOLT_WINTERS" -> "Holt-Winters";
            case "SBA" -> "SBA (intermitente)";
            default -> metodo;
        };
    }

    /** Métrica con dos decimales, o "-" cuando es indefinida. */
    @Nonnull
    private static String dosDecimales(@Nullable Double valor) {
        if (valor == null || !Double.isFinite(valor)) {
            return SIN_METRICA;
        }
        return BigDecimal.valueOf(valor).setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    /**
     * Sesgo escalado con su lectura en lenguaje llano.
     *
     * <p>La convención de signo es {@code real - pronóstico}: por encima
     * de +1 el método se queda corto (tiende a subestimar, se repone de más) y
     * por debajo de -1 se pasa (tiende a sobrestimar, se inmoviliza stock). En
     * el intervalo (-1, 1) el modelo está calibrado.</p>
     */
    @Nonnull
    private static String sesgoLegible(@Nullable Double sesgo) {
        if (sesgo == null || !Double.isFinite(sesgo)) {
            return SIN_METRICA;
        }
        return dosDecimales(sesgo) + " · " + calificadorSesgo(sesgo);
    }

    @Nonnull
    private static String calificadorSesgo(double sesgo) {
        if (sesgo > 1.0d) {
            return "tiende a subestimar";
        }
        if (sesgo < -1.0d) {
            return "tiende a sobrestimar";
        }
        return "calibrado";
    }
}
