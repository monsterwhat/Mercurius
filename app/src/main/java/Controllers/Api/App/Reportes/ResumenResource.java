package Controllers.Api.App.Reportes;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import Models.DTO.ReportChartPayload;
import Models.DTO.ReportChartSeries;
import Models.Departamento;
import Models.Familia;
import Services.DepartamentoService;
import Services.FamiliaService;
import Services.InventarioService;
import Utils.ReportChartJson;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
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

/**
 * Resumen de Inventario for the NEW app surface — port of
 * {@code secured/pages/Inventario/Resumen/index.xhtml}
 * (plan task T19, read-only).
 *
 * <p>Backed by {@link InventarioService}'s resumen queries: the article/stock
 * join is counted, filtered, sorted and paged in SQL, so a request only reads
 * the visible page instead of materialising all 252k enabled articles (with
 * their EAGER price history) just to slice 15 rows. {@link FamiliaService} and
 * {@link DepartamentoService} still supply the filter pickers. The legacy
 * p:tabView becomes a {@code vista} query param (todos|cero|negativos); the
 * stat-cards are links that switch it server-side.</p>
 */
@Path("/app/reportes/inventario/resumen")
@Produces(MediaType.TEXT_HTML)
@RolesAllowed({"admin", "inventario"})
public class ResumenResource {

    private static final String BASE_URL = "/app/reportes/inventario/resumen";

    /** Same bounds the old in-memory {@code Tablas.paginaDe} applied. */
    private static final int TAMANO_MAXIMO = 200;

    @Inject
    @Nonnull
    FamiliaService familiaService;

    @Inject
    @Nonnull
    DepartamentoService departamentoService;

    @Inject
    @Nonnull
    InventarioService inventarioService;

    @Inject
    @Location("pages/reportes/resumen")
    Template pagina;

    @Inject
    @Location("pages/reportes/_tablas/resumen")
    Template tabla;

    @GET
    @Transactional
    public Response get(
            @Context @Nonnull HttpHeaders headers,
            @QueryParam("page") @DefaultValue("1") int page,
            @QueryParam("size") @DefaultValue("15") int size,
            @QueryParam("sort") @Nullable String sort,
            @QueryParam("dir") @DefaultValue("asc") String dir,
            @QueryParam("q") @Nullable String q,
            @QueryParam("familia") @Nullable String familia,
            @QueryParam("departamento") @Nullable String departamento,
            @QueryParam("vista") @Nullable String vista) {

        String tipoVista = normalizarVista(vista);
        Map<String, String> filtros = new LinkedHashMap<>();
        filtros.put("q", q);
        filtros.put("familia", familia);
        filtros.put("departamento", departamento);
        filtros.put("vista", tipoVista);

        List<Familia> familiasEntidad = familiaService.listAll();
        List<Departamento> departamentosEntidad = departamentoService.listAll();
        if (familiasEntidad == null) {
            familiasEntidad = new ArrayList<>();
        }
        if (departamentosEntidad == null) {
            departamentosEntidad = new ArrayList<>();
        }

        Integer familiaId = entero(familia);
        Integer departamentoId = entero(departamento);
        String filtro = q != null ? q.trim() : null;

        // KPI cards are COUNT queries over the whole enabled catalogue: they
        // are not scoped by familia/departamento/q (same as the old counters,
        // which were incremented before the row filters).
        long[] kpis = inventarioService.countResumenInventarioKpis();

        // Same clamps the old in-memory pager applied, so `size` keeps meaning
        // "rows per page" for offsets too.
        int tamanoPagina = Math.min(Math.max(size, 1), TAMANO_MAXIMO);
        int paginaActual = Math.max(page, 1);
        int offset = (int) Math.min((long) (paginaActual - 1) * tamanoPagina,
                Integer.MAX_VALUE);

        // The table is counted, filtered, sorted and sliced in SQL. Only the
        // visible page is materialised; prices are loaded for those rows only
        // (see filasDePagina).
        long totalFilas = inventarioService.countResumenInventario(
                tipoVista, familiaId, departamentoId, filtro);
        List<Object[]> paginaActualDatos = inventarioService.pageResumenInventario(
                tipoVista, familiaId, departamentoId, filtro, sort, dir,
                offset, tamanoPagina);
        List<Map<String, Object>> filas = filasDePagina(paginaActualDatos);

        List<String> chartLabels = new ArrayList<>();
        List<BigDecimal> chartValues = new ArrayList<>();
        for (Object[] grupo : inventarioService.stockResumenPorFamilia(
                tipoVista, familiaId, departamentoId, filtro)) {
            chartLabels.add(grupo[0] != null ? String.valueOf(grupo[0]) : "Sin familia");
            chartValues.add(grupo[1] instanceof BigDecimal total
                    ? total : BigDecimal.ZERO);
        }

        // P2 Phase 2.1 contract: one typed payload, serialized once by the
        // shared helper. The template only carries the marker + JSON script tag;
        // it never concatenates labels or values into JavaScript.
        ReportChartPayload chartPayload = new ReportChartPayload(
                "datos-grafica-resumen",
                "bar",
                "Stock Actual",
                chartLabels,
                List.of(new ReportChartSeries("Stock Actual", chartValues)),
                false,
                "Sin datos para el período");

        int totalPages = Tablas.totalPaginas(totalFilas, size);

        Map<String, Object> model = new LinkedHashMap<>();
        model.put("titulo", "Resumen de Inventario");
        model.put("baseUrl", BASE_URL);
        model.put("columnas", columnas());
        model.put("filas", filas);
        model.put("sortKey", sort);
        model.put("sortDir", dir);
        model.put("page", Math.max(page, 1));
        model.put("size", size);
        model.put("total", totalFilas);
        model.put("totalPages", totalPages);
        model.put("pages", Tablas.ventanaPaginas(page, totalPages));
        model.put("params", Tablas.params(filtros));
        model.put("filtros", filtros);
        model.put("vista", tipoVista);
        model.put("chartPayload", chartPayload);
        model.put("chartJson", ReportChartJson.toScriptJson(chartPayload));
        model.put("todosCount", kpis[0]);
        model.put("ceroCount", kpis[1]);
        model.put("negativoCount", kpis[2]);
        model.put("familias", opciones(familiasEntidad.stream()
                .map(Familia::getId).toList(), familiasEntidad.stream()
                .map(Familia::getNombre).toList()));
        model.put("departamentos", opciones(departamentosEntidad.stream()
                .map(Departamento::getId).toList(), departamentosEntidad.stream()
                .map(Departamento::getNombre).toList()));

        boolean fragmento = headers.getHeaderString("HX-Request") != null;
        Template plantilla = fragmento ? tabla : pagina;
        String html = plantilla.data(model).render();
        return Response.ok(html)
                .type(MediaType.TEXT_HTML_TYPE.withCharset("UTF-8"))
                .build();
    }

    @Nonnull
    private static String normalizarVista(@Nullable String vista) {
        if (vista == null) {
            return "todos";
        }
        return switch (vista) {
            case "cero", "negativos" -> vista;
            default -> "todos";
        };
    }

    /**
     * Turns the page rows returned by
     * {@link InventarioService#pageResumenInventario} into the display maps the
     * table fragment renders. The last price is fetched in one query for these
     * codes only — never for the whole catalogue.
     *
     * <p>Value parity with the old entity loop: stock is always formatted
     * (missing stock = 0), optimal stock and price render {@code "-"} when
     * absent, and family/department render {@code "-"} when the relation is
     * null.</p>
     */
    @Nonnull
    private List<Map<String, Object>> filasDePagina(@Nonnull List<Object[]> pagina) {
        List<Long> codigos = new ArrayList<>(pagina.size());
        for (Object[] fila : pagina) {
            codigos.add(((Number) fila[0]).longValue());
        }
        Map<Long, BigDecimal> precios = inventarioService.ultimosPreciosPorArticulo(codigos);

        List<Map<String, Object>> filas = new ArrayList<>(pagina.size());
        for (Object[] fila : pagina) {
            long codigo = ((Number) fila[0]).longValue();
            BigDecimal stock = fila[5] instanceof BigDecimal valor ? valor : BigDecimal.ZERO;
            filas.add(Tablas.fila(
                    "codigo", codigo,
                    "nombre", fila[1],
                    "codigoBarra", fila[2],
                    "familia", fila[3] != null ? fila[3] : "-",
                    "departamento", fila[4] != null ? fila[4] : "-",
                    "stockActual", Tablas.fmtNumero(stock),
                    "stockOptimo", fila[6] != null ? String.valueOf(fila[6]) : "-",
                    "precio", Tablas.fmtColones(precios.get(codigo))));
        }
        return filas;
    }

    @Nullable
    private static Integer entero(@Nullable String valor) {
        if (valor == null || valor.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(valor.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Nonnull
    private static List<Map<String, Object>> opciones(@Nonnull List<?> ids,
            @Nonnull List<String> nombres) {
        List<Map<String, Object>> opciones = new ArrayList<>();
        for (int i = 0; i < ids.size() && i < nombres.size(); i++) {
            if (nombres.get(i) != null) {
                opciones.add(Tablas.fila("id", ids.get(i), "nombre", nombres.get(i)));
            }
        }
        return opciones;
    }

    @Nonnull
    private static List<Map<String, Object>> columnas() {
        return List.of(
                Map.of("label", "Codigo", "key", "codigo"),
                Map.of("label", "Nombre", "key", "nombre"),
                Map.of("label", "Codigo Barra", "key", "codigoBarra"),
                Map.of("label", "Familia", "key", "familia"),
                Map.of("label", "Departamento", "key", "departamento"),
                Map.of("label", "Stock Actual", "key", "stockActual"),
                Map.of("label", "Stock Optimo", "key", "stockOptimo"),
                Map.of("label", "Precio", "key", "precio"));
    }
}
