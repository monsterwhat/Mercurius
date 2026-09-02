package Controllers.Api.App;

import Models.DTO.ApiResponse;
import Models.ProfitMarginSnapshot;
import Models.ReportesFamiliasYDepartamentos;
import Services.ArticulosService;
import Services.DepartamentoService;
import Services.FamiliaService;
import Services.InventarioService;
import Services.ProfitAnalysisService;
import Services.StockAlertService;
import Utils.ReportExporter;
import com.lowagie.text.DocumentException;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import org.jboss.logging.Logger;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * Download endpoints for the NEW Qute/HTMX app surface (/app world): streams
 * report bytes produced by {@link ReportExporter} as HTTP attachments,
 * replacing the JSF dataExporter / ExternalContext download flows.
 *
 * <p>Dataset keys map 1:1 to the migrated datasets:</p>
 * <ul>
 *   <li>{@code articulos} → "Reporte de Articulos Activos" (pdf) / "Articulos" workbook (xlsx)</li>
 *   <li>{@code inventario} → "Reporte de Ajustes Activos" (pdf)</li>
 *   <li>{@code stock-alerts} → "Alertas de Stock" workbook (xlsx)</li>
 *   <li>{@code profit-margins} → "Snapshots Márgenes" workbook (xlsx)</li>
 *   <li>{@code familias} → "Familias" workbook (xlsx)</li>
 *   <li>{@code departamentos} → "Departamentos" workbook (xlsx)</li>
 *   <li>{@code reportes-familias} → "Ventas por Familia" workbook (xlsx)</li>
 *   <li>{@code reportes-departamentos} → "Ventas por Departamento" workbook (xlsx)</li>
 * </ul>
 *
 * <p>Once T15 enables the declarative policies, every path under
 * {@code /api/app/*} requires an authenticated user; until then this resource
 * relies on the same dormant auth block as the rest of the /api/app surface.</p>
 */
@Path("/api/app/export")
@Produces(MediaType.APPLICATION_OCTET_STREAM)
@Consumes(MediaType.APPLICATION_FORM_URLENCODED)
@Tag(name = "App - Export")
public class ExportResource {

    private static final Logger LOG = Logger.getLogger(ExportResource.class);

    private static final String TYPE_XLSX = "xlsx";
    private static final String TYPE_PDF = "pdf";

    /** {dataset}-{yyyyMMdd HH:mm}.ext, quoted in Content-Disposition. */
    private static final DateTimeFormatter FILE_STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd HH:mm").withLocale(Locale.ROOT);

    @Nonnull
    @Inject
    ArticulosService articulosService;

    @Nonnull
    @Inject
    DepartamentoService departamentoService;

    @Nonnull
    @Inject
    FamiliaService familiaService;

    @Nonnull
    @Inject
    InventarioService inventarioService;

    @Nonnull
    @Inject
    StockAlertService stockAlertService;

    @Nonnull
    @Inject
    ProfitAnalysisService profitAnalysisService;

    /**
     * Streams one of the migrated datasets as an attachment download.
     *
     * @param type    export format: {@code xlsx} or {@code pdf}
     * @param dataset dataset key (see class doc); unknown keys yield 404
     * @param desde   optional ISO {@code yyyy-MM-dd} start bound for the
     *                {@code reportes-familias} / {@code reportes-departamentos}
     *                datasets (blank/invalid → empty workbook)
     * @param hasta   optional ISO {@code yyyy-MM-dd} end bound for the
     *                {@code reportes-familias} / {@code reportes-departamentos}
     *                datasets (blank/invalid → empty workbook)
     */
    @POST
    @Operation(summary = "Stream a dataset export (xlsx/pdf) as an attachment download")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Report bytes streamed as octet-stream attachment"),
        @APIResponse(responseCode = "400", description = "Missing or invalid parameters"),
        @APIResponse(responseCode = "404", description = "Unknown dataset or unsupported format for the dataset"),
        @APIResponse(responseCode = "500", description = "Internal server error")
    })
    public Response download(
            @FormParam("type") @Nullable String type,
            @FormParam("dataset") @Nullable String dataset,
            @FormParam("desde") @Nullable String desde,
            @FormParam("hasta") @Nullable String hasta) {

        if (type == null || type.isBlank()) {
            return badRequest("Falta el parámetro 'type' (xlsx|pdf)");
        }
        if (dataset == null || dataset.isBlank()) {
            return badRequest("Falta el parámetro 'dataset'");
        }
        String normalizedType = type.trim().toLowerCase(Locale.ROOT);
        if (!TYPE_XLSX.equals(normalizedType) && !TYPE_PDF.equals(normalizedType)) {
            return badRequest("Tipo de exportación no soportado: " + type);
        }

        String key = dataset.trim().toLowerCase(Locale.ROOT);
        byte[] bytes;
        try {
            bytes = buildExport(key, normalizedType, desde, hasta);
        } catch (UnsupportedDatasetException e) {
            return notFound(e.getMessage());
        } catch (Exception e) {
            LOG.warn("Error building export " + key + "/" + normalizedType, e);
            return serverError("No se pudo generar la exportación");
        }

        String fileName = key + "-" + LocalDateTime.now().format(FILE_STAMP) + "." + normalizedType;
        return Response.ok(bytes)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fileName + "\"")
                .build();
    }

    private byte[] buildExport(@Nonnull String dataset, @Nonnull String type,
                               @Nullable String desde, @Nullable String hasta)
            throws DocumentException, IOException, UnsupportedDatasetException {
        boolean wantsPdf = TYPE_PDF.equals(type);

        return switch (dataset) {
            case "articulos" -> wantsPdf
                    ? ReportExporter.exportArticulosPdf(orEmpty(articulosService.ListAllEnabled()))
                    : ReportExporter.exportArticulosExcel(orEmpty(articulosService.ListAllEnabled()));
            case "inventario" -> wantsPdf
                    ? ReportExporter.exportInventarioPdf(orEmpty(inventarioService.ListAllEnabled()))
                    : unsupported(dataset, type);
            case "stock-alerts" -> !wantsPdf
                    ? ReportExporter.exportStockAlertsExcel(orEmpty(stockAlertService.getActiveStockAlerts()))
                    : unsupported(dataset, type);
            case "profit-margins" -> !wantsPdf
                    ? ReportExporter.exportProfitMarginSnapshotsExcel(marginSnapshotsLast30Days())
                    : unsupported(dataset, type);
            case "familias" -> !wantsPdf
                    ? ReportExporter.exportFamiliasExcel(orEmpty(familiaService.listAll()))
                    : unsupported(dataset, type);
            case "departamentos" -> !wantsPdf
                    ? ReportExporter.exportDepartamentosExcel(orEmpty(departamentoService.listAllActive()))
                    : unsupported(dataset, type);
            case "reportes-familias" -> !wantsPdf
                    ? ReportExporter.exportVentasPorFamiliaExcel(ventasPorFamilia(desde, hasta))
                    : unsupported(dataset, type);
            case "reportes-departamentos" -> !wantsPdf
                    ? ReportExporter.exportVentasPorDepartamentoExcel(ventasPorDepartamento(desde, hasta))
                    : unsupported(dataset, type);
            default -> unsupported(dataset, type);
        };
    }

    /**
     * Mirrors ProfitAnalysisController's general-analysis defaults: margin
     * snapshots for the last 30 days (null name = all departments/families).
     */
    @Nonnull
    private List<ProfitMarginSnapshot> marginSnapshotsLast30Days() {
        Date endDate = new Date();
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.DAY_OF_MONTH, -30);
        Date startDate = cal.getTime();
        return orEmpty(profitAnalysisService.getMarginTrend(null, "department", startDate, endDate));
    }

    /**
     * Sales-by-family rows for the reportes-familias dataset, mirroring
     * FamiliasResource: both {@code desde} and {@code hasta} are required (the
     * report page shows an empty table otherwise); blank/invalid dates yield an
     * empty workbook.
     */
    @Nonnull
    private List<ReportesFamiliasYDepartamentos> ventasPorFamilia(@Nullable String desde, @Nullable String hasta) {
        Date inicio = fecha(desde, false);
        Date fin = fecha(hasta, true);
        if (inicio == null || fin == null) {
            return new ArrayList<>();
        }
        return orEmpty(inventarioService.getTotalSalesByFamilia(inicio, fin));
    }

    /** Sales-by-department twin of {@link #ventasPorFamilia}. */
    @Nonnull
    private List<ReportesFamiliasYDepartamentos> ventasPorDepartamento(@Nullable String desde, @Nullable String hasta) {
        Date inicio = fecha(desde, false);
        Date fin = fecha(hasta, true);
        if (inicio == null || fin == null) {
            return new ArrayList<>();
        }
        return orEmpty(inventarioService.getTotalSalesByDepartamento(inicio, fin));
    }

    /** Parses an ISO {@code yyyy-MM-dd} input into a Date; null when blank/invalid. */
    @Nullable
    private static Date fecha(@Nullable String iso, boolean finDeDia) {
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
            // Intentionally silent (AGENTS.md logging rule): an unparsable or
            // out-of-range date is caller-data noise, not an application failure;
            // the returned null becomes the explicit "invalid date range" error
            // in the report builders, which is where it gets reported.
            return null;
        }
    }

    private static byte[] unsupported(@Nonnull String dataset, @Nonnull String type) throws UnsupportedDatasetException {
        throw new UnsupportedDatasetException(
                "El dataset '" + dataset + "' no soporta el tipo '" + type + "'");
    }

    private static <T> @Nonnull List<T> orEmpty(@Nullable List<T> list) {
        return list != null ? list : new ArrayList<>();
    }

    @Nonnull
    private static Response badRequest(@Nonnull String message) {
        return Response.status(Response.Status.BAD_REQUEST)
                .type(MediaType.APPLICATION_JSON)
                .entity(ApiResponse.error("VALIDATION_ERROR", message))
                .build();
    }

    @Nonnull
    private static Response notFound(@Nonnull String message) {
        return Response.status(Response.Status.NOT_FOUND)
                .type(MediaType.APPLICATION_JSON)
                .entity(ApiResponse.error("NOT_FOUND", message))
                .build();
    }

    @Nonnull
    private static Response serverError(@Nonnull String message) {
        return Response.serverError()
                .type(MediaType.APPLICATION_JSON)
                .entity(ApiResponse.error("INTERNAL_ERROR", message))
                .build();
    }

    /** Signals an unknown dataset key or a format the dataset does not offer. */
    private static final class UnsupportedDatasetException extends Exception {
        UnsupportedDatasetException(@Nonnull String message) {
            super(message);
        }
    }
}
