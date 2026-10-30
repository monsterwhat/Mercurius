package Controllers.Api.App.Reportes;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collection;
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
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * {@link SeleccionMetodoService#mejorFila(java.util.List)}, que es la única
 * fuente de verdad de la elección. Un artículo sin filas de precisión se muestra
 * como "sin evaluar", nunca como celda vacía ni como error: una columna de
 * exactitud no puede ser la razón por la que la página no carga.</p>
 */
@Path("/app/reportes/inventario/pronosticos")
@Produces(MediaType.TEXT_HTML)
@RolesAllowed({"admin", "inventario"})
public class PronosticosResource {

    private static final Logger LOG = Logger.getLogger(PronosticosResource.class);

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static final String BASE_URL = "/app/reportes/inventario/pronosticos";

    /** Tope de filas por página (mismo límite que el resto de reportes). */
    private static final int TAMANO_MAXIMO = 200;

    /** Marcador de un artículo sin filas de precisión (nunca celda vacía). */
    private static final String SIN_EVALUAR = "sin evaluar";

    /** Sustituto de una métrica indefinida (MASE o sesgo nulos). */
    private static final String SIN_METRICA = "-";

    @Inject
    @Nonnull
    StockForecastService stockForecastService;

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
            @QueryParam("departamento") @Nullable String departamento,
            @QueryParam("familia") @Nullable String familia,
            @QueryParam("seccion") @Nullable String seccion) {

        String vista = normalizarSeccion(seccion);
        int diasPronostico = normalizarDias(dias);
        String deptoSel = departamento == null ? "" : departamento.trim();
        String familiaSel = familia == null ? "" : familia.trim();
        Map<String, String> filtros = new LinkedHashMap<>();
        filtros.put("dias", String.valueOf(diasPronostico));
        filtros.put("seccion", vista);
        if (!deptoSel.isEmpty()) {
            filtros.put("departamento", deptoSel);
        }
        if (!familiaSel.isEmpty()) {
            filtros.put("familia", familiaSel);
        }

        // Una sola lectura agregada del catálogo: reemplaza la hidratación de
        // 252k artículos (con sus precios EAGER) más el historial y el stock
        // por separado. Antes esta página superaba los 30 s.
        int tamanoPagina = Math.min(Math.max(size, 1), TAMANO_MAXIMO);
        int paginaActual = Math.max(page, 1);
        StockForecastService.ReportePronosticos reporte =
                stockForecastService.reportePronosticos(
                        diasPronostico, vista, deptoSel, familiaSel);
        StockForecastService.InventoryHealthReport salud = reporte.salud();

        // La gráfica usa las primeras filas en el orden de la consulta, antes
        // de ordenar para la tabla, igual que antes.
        List<StockForecastService.FilaPronostico> filas =
                new ArrayList<>(reporte.filas());
        List<Map<String, Object>> chartData = new ArrayList<>();
        if (!"salud".equals(vista)) {
            String chartKey = "reorden".equals(vista) ? "cantidadSugerida" : "demandaEstimada";
            for (StockForecastService.FilaPronostico fila : filas) {
                if (chartData.size() >= 12) {
                    break;
                }
                Object valor = "cantidadSugerida".equals(chartKey)
                        ? fila.cantidadSugerida() : fila.demandaEstimada();
                chartData.add(Map.of(
                        "label", fila.articulo(),
                        "value", String.valueOf(valor)));
            }
        }

        // Solo se materializan las filas visibles: la lista compacta se ordena
        // y se recorta antes de construir los mapas de la tabla.
        long totalFilas;
        List<Map<String, Object>> paginaVisible;
        if ("salud".equals(vista)) {
            List<StockForecastService.FilaCritico> criticos = reporte.criticos();
            List<Map<String, Object>> saludFilas = new ArrayList<>(criticos.size());
            for (StockForecastService.FilaCritico critico : criticos) {
                saludFilas.add(Tablas.fila(
                        "articuloCodigo", critico.articuloCodigo(),
                        "articulo", critico.articulo(),
                        "departamento", critico.departamento(),
                        "familia", critico.familia(),
                        "estado", "Cr\u00edtico"));
            }
            Tablas.ordenar(saludFilas, sort, dir);
            totalFilas = saludFilas.size();
            paginaVisible = Tablas.paginaDe(saludFilas, paginaActual, tamanoPagina);
        } else {
            Tablas.ordenar(filas, sort, dir, fila -> valorOrden(fila, sort));
            totalFilas = filas.size();
            List<StockForecastService.FilaPronostico> recorte =
                    Tablas.paginaDe(filas, paginaActual, tamanoPagina);
            paginaVisible = new ArrayList<>(recorte.size());
            for (StockForecastService.FilaPronostico fila : recorte) {
                paginaVisible.add(Tablas.fila(
                        "articuloCodigo", fila.articuloCodigo(),
                        "articulo", fila.articulo(),
                        "departamento", fila.departamento(),
                        "familia", fila.familia(),
                        "stockActual", String.valueOf(fila.stockActual()),
                        "demandaEstimada", String.valueOf(fila.demandaEstimada()),
                        "diasRestantes", fila.diasRestantes(),
                        "cantidadSugerida", String.valueOf(fila.cantidadSugerida()),
                        "recomendacion", fila.recomendacion(),
                        "prioridad", fila.recomendacion()));
            }

            // Accuracy solo de la página visible (máximo `size` artículos).
            Map<Long, Exactitud> exactitudes = exactitudPorArticulo(codigosDe(paginaVisible));
            for (Map<String, Object> fila : paginaVisible) {
                Exactitud acc = exactitudDe(fila, exactitudes);
                fila.put("metodoElegido", acc.metodo());
                fila.put("mase", acc.mase());
                fila.put("sesgo", acc.sesgo());
            }
        }

        int totalPages = Tablas.totalPaginas(totalFilas, tamanoPagina);

        Map<String, Object> model = new LinkedHashMap<>();
        model.put("titulo", "Pron\u00f3sticos de Inventario");
        model.put("baseUrl", BASE_URL);
        model.put("columnas", columnas(vista));
        model.put("filas", paginaVisible);
        model.put("sortKey", sort);
        model.put("sortDir", dir);
        model.put("page", paginaActual);
        model.put("size", tamanoPagina);
        model.put("total", totalFilas);
        model.put("totalPages", totalPages);
        model.put("pages", Tablas.ventanaPaginas(paginaActual, totalPages));
        model.put("params", Tablas.params(filtros));
        model.put("filtros", filtros);
        model.put("seccion", vista);
        model.put("dias", diasPronostico);
        model.put("departamentos", reporte.departamentos());
        model.put("familias", reporte.familias());
        model.put("departamentoSeleccionado", deptoSel);
        model.put("familiaSeleccionada", familiaSel);
        model.put("totalProductos", salud != null ? salud.totalProducts() : 0);
        model.put("saludables", salud != null ? salud.optimal() : 0);
        model.put("stockBajo", salud != null ? salud.lowStock() : 0);
        model.put("sinStock", salud != null ? salud.outOfStock() : 0);
        model.put("chartJson", toJson(chartData));
        model.put("chartTitle", "reorden".equals(vista)
                ? "Cantidad sugerida" : "Demanda pronosticada");
        model.put("chartSubtitle", ("reorden".equals(vista)
                ? "Reposición a " : "Pronóstico a ") + diasPronostico + " días");
        model.put("chartSeriesLabel", "reorden".equals(vista)
                ? "Unidades sugeridas" : "Demanda pronosticada");

        boolean fragmento = headers.getHeaderString("HX-Request") != null;
        Template plantilla = fragmento ? tabla : pagina;
        String html = plantilla.data(model).render();
        return Response.ok(html)
                .type(MediaType.TEXT_HTML_TYPE.withCharset("UTF-8"))
                .build();
    }

    @Nonnull
    private static String toJson(@Nonnull Object data) {
        try {
            return OBJECT_MAPPER.writeValueAsString(data);
        } catch (JsonProcessingException e) {
            LOG.warn("Pronosticos: no se pudo serializar el conjunto de la gráfica", e);
            return "[]";
        }
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

    @Nonnull
    private static List<Map<String, Object>> columnas(@Nonnull String vista) {
        if ("salud".equals(vista)) {
            return List.of(
                    Map.of("label", "Artículo", "key", "articulo"),
                    Map.of("label", "Departamento", "key", "departamento"),
                    Map.of("label", "Familia", "key", "familia"),
                    Map.of("label", "Estado", "key", "estado"));
        }
        if ("reorden".equals(vista)) {
            List<Map<String, Object>> columnas = new ArrayList<>(List.of(
                    Map.of("label", "Artículo", "key", "articulo"),
                    Map.of("label", "Departamento", "key", "departamento"),
                    Map.of("label", "Familia", "key", "familia"),
                    Map.of("label", "Stock Actual", "key", "stockActual"),
                    Map.of("label", "Cantidad Sugerida", "key", "cantidadSugerida"),
                    Map.of("label", "Prioridad", "key", "prioridad")));
            columnas.addAll(columnasExactitud());
            return List.copyOf(columnas);
        }
        List<Map<String, Object>> columnas = new ArrayList<>(List.of(
                Map.of("label", "Artículo", "key", "articulo"),
                Map.of("label", "Departamento", "key", "departamento"),
                Map.of("label", "Familia", "key", "familia"),
                Map.of("label", "Stock Actual", "key", "stockActual"),
                Map.of("label", "Demanda Pronosticada", "key", "demandaEstimada"),
                Map.of("label", "Días Restantes", "key", "diasRestantes"),
                Map.of("label", "Recomendación", "key", "recomendacion")));
        columnas.addAll(columnasExactitud());
        return List.copyOf(columnas);
    }

    /**
     * Valor ordenable de una fila de pronóstico.
     *
     * <p>Se usan los mismos tipos que la celda muestra ({@code stockActual} y
     * {@code demandaEstimada} como números, el resto como texto) para que
     * {@code Tablas} aplique exactamente el comparador que usaba con los mapas
     * de presentación, sin materializar 200k mapas.</p>
     */
    @Nullable
    private static Object valorOrden(@Nonnull StockForecastService.FilaPronostico fila,
                                     @Nullable String sortKey) {
        if (sortKey == null) {
            return null;
        }
        return switch (sortKey) {
            case "articulo" -> fila.articulo();
            case "departamento" -> fila.departamento();
            case "familia" -> fila.familia();
            case "stockActual" -> fila.stockActual();
            case "demandaEstimada" -> fila.demandaEstimada();
            case "diasRestantes" -> fila.diasRestantes();
            case "cantidadSugerida" -> fila.cantidadSugerida();
            case "recomendacion", "prioridad" -> fila.recomendacion();
            default -> null;
        };
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
                Map.of("label", "Método",
                        "help", "Método de pronóstico elegido por backtesting: el de menor MASE entre los elegibles."),
                Map.of("label", "MASE",
                        "help", "Error absoluto medio escalado. Menos de 1 es mejor que el pronóstico ingenuo; 1 es igual; más de 1 es peor."),
                Map.of("label", "Sesgo",
                        "help", "Error medio escalado. Positivo: se pronostica por debajo de la demanda real. Negativo: por encima. Cerca de 0 es mejor."));
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

    /** Exactitud de una fila visible, o el marcador "sin evaluar" si no hay datos. */
    @Nonnull
    private static Exactitud exactitudDe(
            @Nonnull Map<String, Object> fila,
            @Nonnull Map<Long, Exactitud> porArticulo) {
        Object valor = fila.get("articuloCodigo");
        if (valor instanceof Number numero) {
            return porArticulo.getOrDefault(numero.longValue(), Exactitud.SIN_EVALUAR);
        }
        return Exactitud.SIN_EVALUAR;
    }

    /** Códigos de los artículos de la página visible (máximo `size`). */
    @Nonnull
    private static Set<Long> codigosDe(@Nonnull List<Map<String, Object>> filas) {
        Set<Long> codigos = new LinkedHashSet<>();
        for (Map<String, Object> fila : filas) {
            Object valor = fila.get("articuloCodigo");
            if (valor instanceof Number numero) {
                codigos.add(numero.longValue());
            } else if (valor != null) {
                try {
                    codigos.add(Long.valueOf(String.valueOf(valor)));
                } catch (NumberFormatException e) {
                    LOG.debugf("Pronosticos: código de artículo no numérico: %s", valor);
                }
            }
        }
        return codigos;
    }

    /**
     * Lee la precisión de los artículos de la página visible en UNA consulta y
     * resuelve el método elegido de cada uno ya evaluado.
     *
     * <p>La elección se delega a
     * {@link SeleccionMetodoService#mejorFila(java.util.List)}: el desempate por
     * simplicidad es regla del servicio y no se reimplementa aquí.</p>
     *
     * <p>La consulta se limita a los artículos visibles (normalmente 10). La
     * versión anterior pedía la precisión de todo el catálogo y con 1.160
     * artículos convertía esta página en 45 segundos de espera.</p>
     *
     * <p>Si la lectura falla se registra y se degrada a "sin evaluar" para toda
     * la vista: la exactitud es información añadida y no puede ser el motivo de
     * que la página devuelva 500.</p>
     */
    @Nonnull
    private Map<Long, Exactitud> exactitudPorArticulo(
            @Nullable Collection<Long> codigos) {
        if (codigos == null || codigos.isEmpty()) {
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
            Optional<PrecisionPronostico> elegida =
                    SeleccionMetodoService.mejorFila(articulo.getValue());
            if (elegida.isEmpty()) {
                continue;
            }
            PrecisionPronostico fila = elegida.get();
            resultado.put(articulo.getKey(), new Exactitud(
                    nombreMetodo(fila.getMetodo()),
                    dosDecimales(fila.getMase()),
                    sesgoLegible(fila.getSesgo())));
        }
        return resultado;
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
