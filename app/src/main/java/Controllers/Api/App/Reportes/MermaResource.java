package Controllers.Api.App.Reportes;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import Models.Inventario;
import Services.ShrinkageAnalysisService;
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
 * Control de Mermas y Pérdidas for the NEW app surface — port of
 * {@code secured/pages/Inventario/Reportes/Merma/index.xhtml}
 * (plan task T19, read-only).
 *
 * <p>Backed DIRECTLY by the existing {@link ShrinkageAnalysisService} — the
 * same six reads the legacy {@code shrinkageController.refreshData()} ran
 * (totals, percentage, by-cause/by-department maps and the movement list).
 * The legacy per-cause color/label/percentage helpers are reproduced here so
 * the template stays logic-free.</p>
 */
@Path("/app/reportes/inventario/merma")
@Produces(MediaType.TEXT_HTML)
@RolesAllowed({"admin", "inventario"})
public class MermaResource {

    private static final String BASE_URL = "/app/reportes/inventario/merma";

    @Inject
    @Nonnull
    ShrinkageAnalysisService shrinkageAnalysisService;

    @Inject
    @Location("pages/reportes/merma")
    Template pagina;

    @Inject
    @Location("pages/reportes/_tablas/merma")
    Template tabla;

    @GET
    @Transactional
    public Response get(
            @Context @Nonnull HttpHeaders headers,
            @QueryParam("page") @DefaultValue("1") int page,
            @QueryParam("size") @DefaultValue("15") int size,
            @QueryParam("sort") @Nullable String sort,
            @QueryParam("dir") @DefaultValue("asc") String dir,
            @QueryParam("desde") @Nullable String desde,
            @QueryParam("hasta") @Nullable String hasta,
            @QueryParam("seccion") @Nullable String seccion) {

        String vista = normalizarSeccion(seccion);
        Map<String, String> filtros = new LinkedHashMap<>();
        filtros.put("desde", desde);
        filtros.put("hasta", hasta);
        filtros.put("seccion", vista);

        Date inicio = Tablas.fecha(desde, false);
        Date fin = Tablas.fecha(hasta, true);
        if (inicio == null || fin == null) {
            // Legacy ShrinkageController defaults: last 30 days.
            Calendar cal = Calendar.getInstance();
            fin = new Date();
            cal.add(Calendar.DAY_OF_MONTH, -30);
            inicio = cal.getTime();
        }

        BigDecimal totalMerma = shrinkageAnalysisService.getTotalShrinkage(inicio, fin);
        BigDecimal porcentajeMerma =
                shrinkageAnalysisService.getShrinkagePercentage(inicio, fin);
        BigDecimal movimientoTotal =
                shrinkageAnalysisService.getTotalInventoryMovement(inicio, fin);
        List<Inventario> movimientos =
                shrinkageAnalysisService.getShrinkageMovements(inicio, fin);
        Map<String, BigDecimal> porCausa =
                shrinkageAnalysisService.getShrinkageByCause(inicio, fin);
        Map<String, BigDecimal> porDepartamento =
                shrinkageAnalysisService.getShrinkageByDepartment(inicio, fin);

        List<Map<String, Object>> filas = new ArrayList<>();
        if ("causas".equals(vista)) {
            if (porCausa != null) {
                for (Map.Entry<String, BigDecimal> entry : porCausa.entrySet()) {
                    filas.add(Tablas.fila(
                            "causa", etiquetaCausa(entry.getKey()),
                            "total", Tablas.fmtNumero(entry.getValue()),
                            "porcentaje", porcentajeCausa(porCausa, totalMerma, entry.getKey())));
                }
            }
        } else if ("departamentos".equals(vista)) {
            if (porDepartamento != null) {
                for (Map.Entry<String, BigDecimal> entry : porDepartamento.entrySet()) {
                    filas.add(Tablas.fila(
                            "departamento", entry.getKey(),
                            "total", Tablas.fmtNumero(entry.getValue()),
                            "porcentaje", porcentajeCausa(porCausa, totalMerma, entry.getKey())));
                }
            }
        } else {
            if (movimientos != null) {
                for (Inventario mov : movimientos) {
                    filas.add(Tablas.fila(
                            "tipo", etiquetaCausa(mov.getTipoMovimiento()),
                            "articulo", mov.getArticulo() != null
                                    ? mov.getArticulo().getNombre() : "-",
                            "cantidad", Tablas.fmtNumero(mov.getCantidad()),
                            "fechaMovimiento", Tablas.fmtFechaHora(mov.getFechaMovimiento()),
                            "notas", mov.getNotas(),
                            "usuario", mov.getUsuario() != null
                                    ? mov.getUsuario().getUsername() : "-"));
                }
            }
        }

        Tablas.ordenar(filas, sort, dir);
        long totalFilas = filas.size();
        int totalPages = Tablas.totalPaginas(totalFilas, size);

        Map<String, Object> model = new LinkedHashMap<>();
        model.put("titulo", "Control de Mermas y Pérdidas");
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
        model.put("hayDatos", movimientos != null && !movimientos.isEmpty());
        model.put("totalMerma", Tablas.fmtNumero(totalMerma));
        model.put("porcentajeMerma", Tablas.fmtNumero(porcentajeMerma));
        model.put("movimientoTotal", Tablas.fmtNumero(movimientoTotal));
        model.put("registros", movimientos != null ? movimientos.size() : 0);

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
            return "causas";
        }
        return switch (seccion) {
            case "departamentos", "detalle" -> seccion;
            default -> "causas";
        };
    }

    /** Legacy ShrinkageController.getCauseLabel parity. */
    @Nonnull
    private static String etiquetaCausa(@Nullable String tipo) {
        if (tipo == null) {
            return "-";
        }
        return switch (tipo) {
            case "Merma" -> "Merma General";
            case "Perdida/Robo" -> "P\u00e9rdida/Robo";
            case "Vencimiento" -> "Vencimiento";
            case "Da\u00f1o" -> "Da\u00f1o";
            default -> tipo;
        };
    }

    /** Legacy ShrinkageController.getCausePercentage parity. */
    @Nonnull
    private static String porcentajeCausa(@Nullable Map<String, BigDecimal> porCausa,
            @Nullable BigDecimal totalMerma, @Nullable String tipo) {
        if (porCausa == null || totalMerma == null
                || totalMerma.compareTo(BigDecimal.ZERO) == 0) {
            return "0.0";
        }
        BigDecimal causa = porCausa.getOrDefault(tipo, BigDecimal.ZERO);
        return causa.abs()
                .divide(totalMerma.abs(), 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .setScale(1, RoundingMode.HALF_UP).toPlainString();
    }

    @Nonnull
    private static List<Map<String, Object>> columnas(@Nonnull String vista) {
        if ("causas".equals(vista)) {
            return List.of(
                    Map.of("label", "Causa", "key", "causa"),
                    Map.of("label", "Total", "key", "total"),
                    Map.of("label", "%", "key", "porcentaje"));
        }
        if ("departamentos".equals(vista)) {
            return List.of(
                    Map.of("label", "Departamento", "key", "departamento"),
                    Map.of("label", "Total Mermas", "key", "total"),
                    Map.of("label", "% del Total", "key", "porcentaje"));
        }
        return List.of(
                Map.of("label", "Tipo", "key", "tipo"),
                Map.of("label", "Artículo", "key", "articulo"),
                Map.of("label", "Cantidad", "key", "cantidad"),
                Map.of("label", "Fecha", "key", "fechaMovimiento"),
                Map.of("label", "Notas", "key", "notas"),
                Map.of("label", "Usuario", "key", "usuario"));
    }
}
