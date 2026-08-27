package Controllers.Api.App;

import Models.DTO.ApiResponse;
import Models.DTO.ShrinkageReportDTO;
import Models.Inventario;
import Services.ShrinkageAnalysisService;
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
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * Shrinkage (mermas y pérdidas) analysis for the JSON API surface — port of
 * the legacy {@code shrinkageController.refreshData()} reads (the same six
 * service calls the HTML surface {@code Controllers.Api.App.Reportes.MermaResource}
 * renders).
 *
 * <p><b>Behavior parity contract:</b></p>
 * <ul>
 *   <li>Date window: ISO {@code desde}/{@code hasta} (hasta = end of day);
 *       when either is absent the legacy ShrinkageController default applies
 *       — last 30 days.</li>
 *   <li>All six figures come verbatim from
 *       {@link ShrinkageAnalysisService}: total, percentage, movement total,
 *       by-cause map (all four causes present, zero-filled), by-department
 *       map and the movement list.</li>
 *   <li>{@code departamento} is an API-surface convenience filter with NO
 *       legacy input counterpart: when non-blank it narrows the movement list
 *       and the by-department map to that department name (case-insensitive).
 *       The totals/percentage stay service-verbatim for the whole window.</li>
 * </ul>
 */
@Path("/api/app/shrinkage")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed({"admin", "inventario"})
@Tag(name = "App - Reportes")
public class ShrinkageResource {

    private static final Logger LOG = Logger.getLogger(ShrinkageResource.class.getName());

    @Inject
    @Nonnull
    ShrinkageAnalysisService shrinkageAnalysisService;

    /**
     * Full shrinkage analysis for one date window.
     */
    @GET
    @Transactional
    @Operation(summary = "Shrinkage analysis (legacy shrinkageController.refreshData parity)")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Analysis report"),
        @APIResponse(responseCode = "401", description = "Not authenticated"),
        @APIResponse(responseCode = "403", description = "Role not allowed"),
        @APIResponse(responseCode = "500", description = "Internal server error")
    })
    public Response analisis(
            @QueryParam("departamento") @Nullable @Parameter(description = "Optional department-name filter (movements + by-department map)") String departamento,
            @QueryParam("desde") @Nullable @Parameter(description = "Window start (ISO yyyy-MM-dd)") String desde,
            @QueryParam("hasta") @Nullable @Parameter(description = "Window end, inclusive (ISO yyyy-MM-dd)") String hasta) {
        try {
            Date inicio = parseFechaIso(desde, false);
            Date fin = parseFechaIso(hasta, true);
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
            Map<String, BigDecimal> mermaPorCausa =
                    shrinkageAnalysisService.getShrinkageByCause(inicio, fin);
            Map<String, BigDecimal> mermaPorDepartamento =
                    shrinkageAnalysisService.getShrinkageByDepartment(inicio, fin);
            List<Inventario> movimientos =
                    shrinkageAnalysisService.getShrinkageMovements(inicio, fin);

            String filtroDepartamento = normalizeDepartamento(departamento);
            if (filtroDepartamento != null) {
                mermaPorDepartamento = filtrarPorDepartamento(mermaPorDepartamento, filtroDepartamento);
            }

            List<ShrinkageReportDTO.MovimientoDTO> filas = new ArrayList<>();
            for (Inventario mov : movimientos) {
                if (filtroDepartamento != null
                        && !filtroDepartamento.equalsIgnoreCase(nombreDepartamentoDe(mov))) {
                    continue;
                }
                filas.add(toMovimientoDTO(mov));
            }

            ShrinkageReportDTO reporte = new ShrinkageReportDTO(
                    inicio, fin, totalMerma, porcentajeMerma, movimientoTotal,
                    mermaPorCausa, mermaPorDepartamento, filas);
            return Response.ok(ApiResponse.ok(reporte)).build();
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Error generando el análisis de mermas", e);
            return Response.serverError()
                    .entity(ApiResponse.error("INTERNAL_ERROR", "Error generando el análisis de mermas"))
                    .build();
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    @Nullable
    private static String normalizeDepartamento(@Nullable String departamento) {
        if (departamento == null || departamento.isBlank()) {
            return null;
        }
        return departamento.trim();
    }

    /** Narrows the by-department map to the requested name (case-insensitive). */
    @Nonnull
    private static Map<String, BigDecimal> filtrarPorDepartamento(
            @Nonnull Map<String, BigDecimal> porDepartamento, @Nonnull String departamento) {
        Map<String, BigDecimal> filtrado = new LinkedHashMap<>();
        for (Map.Entry<String, BigDecimal> entry : porDepartamento.entrySet()) {
            if (departamento.equalsIgnoreCase(entry.getKey())) {
                filtrado.put(entry.getKey(), entry.getValue());
            }
        }
        return filtrado;
    }

    @Nullable
    private static String nombreDepartamentoDe(@Nullable Inventario mov) {
        if (mov == null || mov.getArticulo() == null || mov.getArticulo().getDepartamento() == null) {
            return null;
        }
        return mov.getArticulo().getDepartamento().getNombre();
    }

    /** Flattened movement mapper (relations per LoteDTO convention). */
    @Nonnull
    private static ShrinkageReportDTO.MovimientoDTO toMovimientoDTO(@Nonnull Inventario mov) {
        return new ShrinkageReportDTO.MovimientoDTO(
                mov.getCodigo(),
                mov.getArticulo() != null ? mov.getArticulo().getCodigo() : null,
                mov.getArticulo() != null ? mov.getArticulo().getNombre() : null,
                mov.getCantidad(),
                mov.getTipoMovimiento(),
                mov.getFechaMovimiento(),
                mov.getNotas(),
                mov.getUsuario() != null ? mov.getUsuario().getId() : null,
                mov.getUsuario() != null ? mov.getUsuario().getUsername() : null);
    }

    /** ISO yyyy-MM-dd → Date; end-of-day when requested; null when blank/invalid (Tablas.fecha parity). */
    @Nullable
    private static Date parseFechaIso(@Nullable String iso, boolean finDeDia) {
        if (iso == null || iso.isBlank()) {
            return null;
        }
        try {
            LocalDate localDate = LocalDate.parse(iso.trim());
            if (finDeDia) {
                return Date.from(localDate.atTime(23, 59, 59)
                        .atZone(ZoneId.systemDefault()).toInstant());
            }
            return Date.from(localDate.atStartOfDay(ZoneId.systemDefault()).toInstant());
        } catch (RuntimeException e) {
            return null;
        }
    }
}
