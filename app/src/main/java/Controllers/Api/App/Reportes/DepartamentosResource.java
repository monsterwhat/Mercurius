package Controllers.Api.App.Reportes;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import Models.ReportesFamiliasYDepartamentos;
import Services.InventarioService;
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
 * Reporte de ventas por departamento for the NEW app surface — port of
 * {@code secured/pages/Articulos/Reportes/Departamento/index.xhtml}
 * (plan task T19, read-only).
 *
 * <p>Backed DIRECTLY by {@link InventarioService#getTotalSalesByDepartamento(
 * Date, Date)} — the same aggregate the legacy
 * {@code ReportesDiariosController.cargarVentasDepartamento()} rendered. The
 * footer total uses {@link ReportesFamiliasYDepartamentos#totalReportes(List)},
 * identical to the legacy facet.</p>
 */
@Path("/app/reportes/articulos/departamentos")
@Produces(MediaType.TEXT_HTML)
@RolesAllowed({"admin", "inventario"})
public class DepartamentosResource {

    private static final String BASE_URL = "/app/reportes/articulos/departamentos";

    @Inject
    @Nonnull
    InventarioService inventarioService;

    @Inject
    @Location("pages/reportes/departamentos")
    Template pagina;

    @Inject
    @Location("pages/reportes/_tablas/departamentos")
    Template tabla;

    @GET
    @Transactional
    public Response get(
            @Context @Nonnull HttpHeaders headers,
            @QueryParam("page") @DefaultValue("1") int page,
            @QueryParam("size") @DefaultValue("20") int size,
            @QueryParam("sort") @Nullable String sort,
            @QueryParam("dir") @DefaultValue("asc") String dir,
            @QueryParam("desde") @Nullable String desde,
            @QueryParam("hasta") @Nullable String hasta) {

        Map<String, String> filtros = new LinkedHashMap<>();
        filtros.put("desde", desde);
        filtros.put("hasta", hasta);

        Date inicio = Tablas.fecha(desde, false);
        Date fin = Tablas.fecha(hasta, true);

        List<Map<String, Object>> filas = new ArrayList<>();
        BigDecimal total = null;
        boolean consultaCompleta = inicio != null && fin != null;

        if (consultaCompleta) {
            List<ReportesFamiliasYDepartamentos> reportes =
                    inventarioService.getTotalSalesByDepartamento(inicio, fin);
            if (reportes != null) {
                total = ReportesFamiliasYDepartamentos.totalReportes(reportes);
                for (ReportesFamiliasYDepartamentos reporte : reportes) {
                    filas.add(Tablas.fila(
                            "nombre", reporte.getNombre(),
                            "cantidad", Tablas.fmtNumero(reporte.getCantidad()),
                            "porcentaje", Tablas.fmtNumero(reporte.getPorcentaje())));
                }
            }
        }

        Tablas.ordenar(filas, sort, dir);
        long totalFilas = filas.size();
        int totalPages = Tablas.totalPaginas(totalFilas, size);

        Map<String, Object> model = new LinkedHashMap<>();
        model.put("titulo", "Reporte de Ventas por Departamento");
        model.put("baseUrl", BASE_URL);
        model.put("columnas", columnas());
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
        model.put("consultaCompleta", consultaCompleta);
        model.put("totalGeneral", total != null ? Tablas.fmtNumero(total) : null);

        boolean fragmento = headers.getHeaderString("HX-Request") != null;
        Template plantilla = fragmento ? tabla : pagina;
        String html = plantilla.data(model).render();
        return Response.ok(html)
                .type(MediaType.TEXT_HTML_TYPE.withCharset("UTF-8"))
                .build();
    }

    @Nonnull
    private static List<Map<String, Object>> columnas() {
        return List.of(
                Map.of("label", "Departamento", "key", "nombre"),
                Map.of("label", "Total", "key", "cantidad"),
                Map.of("label", "Porcentaje", "key", "porcentaje"));
    }
}
