package Controllers.Api.App.Reportes;

import static Controllers.Api.App.AppRecursoBase.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import Models.DepartamentoMetrico;
import Services.DepartamentoMetricoService;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import org.jboss.logging.Logger;

/**
 * Independent report screen for the supplier performance ranking, extracted
 * from the legacy mixed {@code /app/categorias} page as decision D5 of the
 * reports UI audit.
 *
 * <p>Supplier == {@link Models.Departamento}: no {@code Proveedor} entity is
 * introduced. The ranking is backed directly by
 * {@link DepartamentoMetricoService} (latest metric per department, score,
 * invoices, totals), which is the same service the embedded scorecard already
 * used. No business logic was duplicated: this resource only reads the existing
 * aggregates and renders them through {@code pages/reportes/proveedores}.</p>
 */
@Path("/app/reportes/proveedores")
@Produces(MediaType.TEXT_HTML)
@RolesAllowed({"admin", "inventario"})
public class ProveedoresReportesResource {

    private static final Logger LOG = Logger.getLogger(ProveedoresReportesResource.class);

    /** Hard cap on supplier-ranking rows (mirrors CategoriaResource.METRICAS_TOPE). */
    private static final int METRICAS_TOPE = 200;

    @Inject
    DepartamentoMetricoService departamentoMetricoService;

    @Inject
    @Location("pages/reportes/proveedores")
    Template pagina;

    @GET
    @Transactional
    public Response get() {
        try {
            List<Map<String, Object>> filas = new ArrayList<>();
            for (DepartamentoMetrico m : orEmpty(departamentoMetricoService.listUltimasPorDepartamento(METRICAS_TOPE))) {
                Map<String, Object> fila = new LinkedHashMap<>();
                String proveedor = (m.getDepartamento() != null && m.getDepartamento().getNombre() != null)
                        ? m.getDepartamento().getNombre() : "Sin nombre";
                fila.put("proveedor", proveedor);
                double score = m.getScore();
                fila.put("score", score);
                fila.put("barColor", barColor(score));
                fila.put("facturas", m.getTotalFacturasRecibidas());
                fila.put("pagadas", m.getFacturasPagadas());
                fila.put("montoTotal", m.getMontoTotalCompras());
                fila.put("onTime", m.getTasaOnTimeDelivery());
                filas.add(fila);
            }

            Map<String, Object> model = new LinkedHashMap<>();
            model.put("filas", filas);
            model.put("totalProveedores", departamentoMetricoService.countDepartamentosConMetricas());
            model.put("scorePromedio", departamentoMetricoService.avgScore());
            model.put("comprasTotales", departamentoMetricoService.sumMontoTotalCompras());

            String html = pagina.data(model).render();
            return Response.ok(html)
                    .type(MediaType.TEXT_HTML_TYPE.withCharset("UTF-8"))
                    .build();
        } catch (RuntimeException e) {
            LOG.error("Error renderizando el ranking de proveedores", e);
            return Response.serverError()
                    .entity("No se pudo renderizar el ranking de proveedores")
                    .type(MediaType.TEXT_HTML_TYPE.withCharset("UTF-8"))
                    .build();
        }
    }

    /** Progress-bar colour parity with the embedded scorecard. */
    private static String barColor(double score) {
        if (score >= 80) {
            return "is-success";
        }
        if (score >= 60) {
            return "is-warning";
        }
        return "is-danger";
    }
}
