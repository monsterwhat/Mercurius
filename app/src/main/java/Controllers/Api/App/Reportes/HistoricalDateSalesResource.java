package Controllers.Api.App.Reportes;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import Models.Articulos.Articulos;
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

        List<String> allProductos = new ArrayList<>();
        Set<String> allFamilias = new TreeSet<>();
        Set<String> allDepartamentos = new TreeSet<>();
        List<Articulos> todosArticulos = articulosService.listAll();
        if (todosArticulos == null) todosArticulos = List.of();
        for (Articulos a : todosArticulos) {
            if (a.getNombre() != null && !a.getNombre().isBlank() && !allProductos.contains(a.getNombre().trim())) {
                allProductos.add(a.getNombre().trim());
            }
            if (a.getFamilia() != null && a.getFamilia().getNombre() != null
                    && !a.getFamilia().getNombre().isBlank()) {
                allFamilias.add(a.getFamilia().getNombre().trim());
            }
            if (a.getDepartamento() != null && a.getDepartamento().getNombre() != null
                    && !a.getDepartamento().getNombre().isBlank()) {
                allDepartamentos.add(a.getDepartamento().getNombre().trim());
            }
        }
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

        String html = pagina.data(model).render();
        return Response.ok(html)
                .type(MediaType.TEXT_HTML_TYPE.withCharset("UTF-8"))
                .build();
    }
}
