package Controllers.Api.App.Reportes;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import Services.ArticulosService;
import Services.SalesTrendService;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * Read-only report page that charts historical sales for one calendar day/month
 * across multiple years (e.g. every 27/08 for the last N years), including the
 * overall total and the top products sold on that date.
 */
@Path("/app/reportes/articulos/ventas-fecha-historica")
@Produces(MediaType.TEXT_HTML)
@RolesAllowed({"admin", "inventario"})
public class HistoricalDateSalesResource {

    private static final String BASE_URL = "/app/reportes/articulos/ventas-fecha-historica";

    @Inject
    @Nonnull
    ArticulosService articulosService;

    @Inject
    @Nonnull
    SalesTrendService salesTrendService;

    @Inject
    @Nonnull
    @Location("pages/reportes/ventas-fecha-historica")
    Template pagina;

    @GET
    @Transactional
    public Response get(
            @QueryParam("mes") @Nullable Integer mes,
            @QueryParam("dia") @Nullable Integer dia,
            @QueryParam("anos") @DefaultValue("6") Integer anos,
            @QueryParam("dimension") @Nullable String dimension,
            @QueryParam("items") @Nullable List<String> items) {

        int resolvedMes = mes != null ? mes : java.time.LocalDate.now().getMonthValue();
        int resolvedDia = dia != null ? dia : java.time.LocalDate.now().getDayOfMonth();
        int resolvedAnos = anos != null ? anos : 6;
        String resolvedDimension = (dimension == null || dimension.isBlank()) ? "productos" : dimension.trim();

        Set<String> selectedItems = new LinkedHashSet<>(items != null ? items : List.of());

        Set<String> productosSet = new LinkedHashSet<>();
        Set<String> allFamilias = new TreeSet<>();
        Set<String> allDepartamentos = new TreeSet<>();
        // Projection, not entity hydration: this report only needs the distinct
        // option labels, and 6k article rows per request was pure overhead.
        for (Object[] row : articulosService.listReporteFilterOptions()) {
            String nombre = blankToNull(row.length > 0 ? row[0] : null);
            if (nombre != null) {
                productosSet.add(nombre);
            }
            String familia = blankToNull(row.length > 1 ? row[1] : null);
            if (familia != null) {
                allFamilias.add(familia);
            }
            String departamento = blankToNull(row.length > 2 ? row[2] : null);
            if (departamento != null) {
                allDepartamentos.add(departamento);
            }
        }
        List<String> allProductos = new ArrayList<>(productosSet);
        allProductos.sort(String.CASE_INSENSITIVE_ORDER);

        List<String> opciones;
        if ("familias".equals(resolvedDimension)) {
            opciones = new ArrayList<>(allFamilias);
        } else if ("departamentos".equals(resolvedDimension)) {
            opciones = new ArrayList<>(allDepartamentos);
        } else {
            opciones = allProductos;
        }

        Map<String, String> filtros = new LinkedHashMap<>();
        filtros.put("mes", String.valueOf(resolvedMes));
        filtros.put("dia", String.valueOf(resolvedDia));
        filtros.put("anos", String.valueOf(resolvedAnos));
        filtros.put("dimension", resolvedDimension);
        filtros.put("items", String.join("|", selectedItems));

        SalesTrendService.SameDateHistory resultado = null;
        try {
            resultado = salesTrendService.getSameDateSalesHistory(
                    resolvedMes, resolvedDia, resolvedAnos, resolvedDimension,
                    new ArrayList<>(selectedItems));
        } catch (RuntimeException ex) {
            resultado = null;
        }

        List<Map<String, Object>> resumenAnual = new ArrayList<>();
        List<Map<String, Object>> productosResultado = new ArrayList<>();
        if (resultado != null) {
            for (int i = 0; i < resultado.getYears().size(); i++) {
                resumenAnual.add(Map.of(
                        "anio", resultado.getYears().get(i),
                        "total", resultado.getTotalSales().get(i),
                        "transacciones", resultado.getTransactionCounts().get(i)));
            }
            for (SalesTrendService.ProductYearSeries p : resultado.getProducts()) {
                java.math.BigDecimal total = java.math.BigDecimal.ZERO;
                for (java.math.BigDecimal v : p.getYearlySales()) {
                    if (v != null) total = total.add(v);
                }
                productosResultado.add(Map.of(
                        "nombre", p.getProductName(),
                        "total", total,
                        "detalle", String.join(" / ", p.getYearlySales().stream().map(String::valueOf).toList())));
            }
        }

        Map<String, Object> model = new LinkedHashMap<>();
        model.put("titulo", "Ventas por Fecha Histórica");
        model.put("baseUrl", BASE_URL);
        model.put("filtros", filtros);
        model.put("dimension", resolvedDimension);
        model.put("productos", allProductos);
        model.put("familias", new ArrayList<>(allFamilias));
        model.put("departamentos", new ArrayList<>(allDepartamentos));
        model.put("opciones", opciones);
        model.put("itemsSeleccionados", selectedItems);
        model.put("resultado", resultado);
        model.put("resumenAnual", resumenAnual);
        model.put("productosResultado", productosResultado);

        String html = pagina.data(model).render();
        return Response.ok(html)
                .type(MediaType.TEXT_HTML_TYPE.withCharset("UTF-8"))
                .build();
    }

    /** Trims a projection column and maps blank/null to null. */
    private static @Nullable String blankToNull(@Nullable Object value) {
        if (value == null) {
            return null;
        }
        String text = value.toString().trim();
        return text.isEmpty() ? null : text;
    }
}
