package Controllers.Api.App.Reportes;

import static Controllers.Api.App.AppRecursoBase.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import Models.ComprobantesRecibidos;
import Models.DepartamentoMetrico;
import Services.DepartamentoMetricoService;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
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
    EntityManager em;

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
            if (filas.isEmpty()) {
                // La tabla de metricas se alimenta de facturas recibidas cuyo
                // EMISOR coincide por nombre con un departamento. Los proveedores
                // modernos no estan en ese catalogo (los departamentos vienen de
                // vSuper), asi que el ranking salia vacio con 2.021 facturas
                // recibidas. Este fallback agrega las facturas por emisor, que es
                // el dato que el negocio ya tiene, sin inventar departamentos.
                llenarDesdeFacturasRecibidas(filas);
                model.put("origen", "facturas recibidas");
                model.put("totalProveedores", filas.size());
                model.put("scorePromedio", promedio(filas));
                model.put("comprasTotales", sumaMontos(filas));
            } else {
                model.put("origen", "métricas por departamento");
                model.put("totalProveedores", departamentoMetricoService.countDepartamentosConMetricas());
                model.put("scorePromedio", departamentoMetricoService.avgScore());
                model.put("comprasTotales", departamentoMetricoService.sumMontoTotalCompras());
            }
            model.put("filas", filas);

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

    /**
     * Ranking de respaldo: agrupa las facturas recibidas por emisor. El score
     * es la proporcion pagada (no hay dato de entregas a tiempo sin la metrica
     * de departamento, por eso onTime queda null y la tabla lo muestra "-").
     */
    private void llenarDesdeFacturasRecibidas(List<Map<String, Object>> filas) {
        List<Object[]> agregados = em.createQuery(
                "SELECT em.nombre, COUNT(cr), "
                        + "SUM(CASE WHEN cr.paid = true THEN 1 ELSE 0 END), "
                        + "COALESCE(SUM(r.totalComprobante), 0) "
                        + "FROM ComprobantesRecibidos cr "
                        + "LEFT JOIN cr.encabezado e "
                        + "LEFT JOIN e.emisor em "
                        + "LEFT JOIN cr.resumen r "
                        + "WHERE em.nombre IS NOT NULL "
                        + "GROUP BY em.nombre ORDER BY 4 DESC",
                Object[].class)
                .setMaxResults(METRICAS_TOPE)
                .getResultList();
        for (Object[] row : agregados) {
            String nombre = (String) row[0];
            long facturas = ((Number) row[1]).longValue();
            long pagadas = row[2] == null ? 0L : ((Number) row[2]).longValue();
            java.math.BigDecimal monto = row[3] == null
                    ? java.math.BigDecimal.ZERO : (java.math.BigDecimal) row[3];
            double score = facturas == 0 ? 0 : (pagadas * 100.0) / facturas;
            Map<String, Object> fila = new LinkedHashMap<>();
            fila.put("proveedor", nombre);
            fila.put("score", score);
            fila.put("barColor", barColor(score));
            fila.put("facturas", facturas);
            fila.put("pagadas", pagadas);
            fila.put("montoTotal", monto);
            fila.put("onTime", null);
            filas.add(fila);
        }
    }

    private static double promedio(List<Map<String, Object>> filas) {
        if (filas.isEmpty()) {
            return 0;
        }
        double suma = 0;
        for (Map<String, Object> fila : filas) {
            suma += ((Number) fila.get("score")).doubleValue();
        }
        return suma / filas.size();
    }

    private static java.math.BigDecimal sumaMontos(List<Map<String, Object>> filas) {
        java.math.BigDecimal suma = java.math.BigDecimal.ZERO;
        for (Map<String, Object> fila : filas) {
            suma = suma.add((java.math.BigDecimal) fila.get("montoTotal"));
        }
        return suma;
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
