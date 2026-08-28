package Controllers.Api.App;

import Models.DTO.ApiResponse;
import Models.ProfitMarginSnapshot;
import Services.ArticulosService;
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
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * Download endpoints for the NEW Qute/HTMX app surface (/app world): streams
 * report bytes produced by {@link ReportExporter} as HTTP attachments,
 * replacing the JSF dataExporter / ExternalContext download flows.
 *
 * <p>Dataset keys map 1:1 to the four migrated datasets:</p>
 * <ul>
 *   <li>{@code articulos} → "Reporte de Articulos Activos" (pdf)</li>
 *   <li>{@code inventario} → "Reporte de Ajustes Activos" (pdf)</li>
 *   <li>{@code stock-alerts} → "Alertas de Stock" workbook (xlsx)</li>
 *   <li>{@code profit-margins} → "Snapshots Márgenes" workbook (xlsx)</li>
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

    private static final Logger LOG = Logger.getLogger(ExportResource.class.getName());

    private static final String TYPE_XLSX = "xlsx";
    private static final String TYPE_PDF = "pdf";

    /** {dataset}-{yyyyMMdd HH:mm}.ext, quoted in Content-Disposition. */
    private static final DateTimeFormatter FILE_STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd HH:mm").withLocale(Locale.ROOT);

    @Nonnull
    ArticulosService articulosService;

    @Nonnull
    InventarioService inventarioService;

    @Nonnull
    StockAlertService stockAlertService;

    @Nonnull
    ProfitAnalysisService profitAnalysisService;

    /**
     * Streams one of the migrated datasets as an attachment download.
     *
     * @param type    export format: {@code xlsx} or {@code pdf}
     * @param dataset dataset key (see class doc); unknown keys yield 404
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
            @FormParam("dataset") @Nullable String dataset) {

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
            bytes = buildExport(key, normalizedType);
        } catch (UnsupportedDatasetException e) {
            return notFound(e.getMessage());
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Error building export " + key + "/" + normalizedType, e);
            return serverError("No se pudo generar la exportación");
        }

        String fileName = key + "-" + LocalDateTime.now().format(FILE_STAMP) + "." + normalizedType;
        return Response.ok(bytes)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fileName + "\"")
                .build();
    }

    private byte[] buildExport(@Nonnull String dataset, @Nonnull String type)
            throws DocumentException, IOException, UnsupportedDatasetException {
        boolean wantsPdf = TYPE_PDF.equals(type);

        return switch (dataset) {
            case "articulos" -> wantsPdf
                    ? ReportExporter.exportArticulosPdf(orEmpty(articulosService.ListAllEnabled()))
                    : unsupported(dataset, type);
            case "inventario" -> wantsPdf
                    ? ReportExporter.exportInventarioPdf(orEmpty(inventarioService.ListAllEnabled()))
                    : unsupported(dataset, type);
            case "stock-alerts" -> !wantsPdf
                    ? ReportExporter.exportStockAlertsExcel(orEmpty(stockAlertService.getActiveStockAlerts()))
                    : unsupported(dataset, type);
            case "profit-margins" -> !wantsPdf
                    ? ReportExporter.exportProfitMarginSnapshotsExcel(marginSnapshotsLast30Days())
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
