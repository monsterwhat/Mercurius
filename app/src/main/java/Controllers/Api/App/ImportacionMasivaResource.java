package Controllers.Api.App;

import Models.DTO.ApiResponse;
import Models.DTO.ImportacionMasivaResultado;
import Models.ImportacionMasivaEntidad;
import Services.ImportacionMasivaService;
import Services.ImportacionMasivaService.EsquemaInvalidoException;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.RestForm;
import org.jboss.resteasy.reactive.multipart.FileUpload;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * Bulk import endpoints for the NEW Qute/HTMX app surface — the write-side
 * mirror of {@link ExportResource}.
 *
 * <p>Three endpoints, all gated by the same authorization contract the export
 * surface uses ({@code admin} or {@code registro}, the role the import/export
 * buttons check in the templates):</p>
 * <ul>
 *   <li>{@code GET /api/app/importacion-masiva/esquema} — the declared columns
 *       of every target, so a caller can build a sheet without downloading one.</li>
 *   <li>{@code GET /api/app/importacion-masiva/plantilla?entidad=&formato=} —
 *       the template workbook/csv as an attachment download (the inverse of the
 *       export download).</li>
 *   <li>{@code POST /api/app/importacion-masiva} — multipart upload of
 *       {@code archivo} with {@code entidad} and {@code simulacion}.</li>
 * </ul>
 *
 * <p><b>Dry run first.</b> {@code simulacion=true} (the default) validates the
 * schema and every row and returns the full per-row ledger without writing
 * anything; the operator then re-posts with {@code simulacion=false} to apply.
 * The response counts reconcile as
 * {@code totalFilas == creados + actualizados + omitidas + rechazadas} and no row
 * is ever dropped silently — each one carries either its acceptance reason or its
 * rejection reason, in Spanish.</p>
 *
 * <p>Note for the HTTP permission policy: {@code quarkus.http.auth.permission.secured.paths}
 * enumerates the real {@code /api/app/*} sub-APIs. This resource is protected by
 * its class-level {@code @RolesAllowed}, which is the authoritative gate, but
 * adding {@code /api/app/importacion-masiva} and
 * {@code /api/app/importacion-masiva/*} to that property keeps it consistent with
 * the rest of the surface (and makes an anonymous call answer the login
 * challenge rather than fall through to the JSON 404 fallback).</p>
 */
@Path("/api/app/importacion-masiva")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed({"admin", "registro"})
@Tag(name = "App - Importación Masiva")
public class ImportacionMasivaResource {

    private static final Logger LOG = Logger.getLogger(ImportacionMasivaResource.class);

    private static final String FORMATO_XLSX = "xlsx";
    private static final String FORMATO_CSV = "csv";

    @Nonnull
    @Inject
    ImportacionMasivaService importacionMasivaService;

    @Nonnull
    @Inject
    SecurityIdentity identity;

    /**
     * POST /api/app/importacion-masiva — validates (and, when
     * {@code simulacion=false}, applies) an uploaded {@code .xlsx}/{@code .csv}
     * for one of the four catalogs.
     */
    @POST
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Operation(summary = "Importar clientes, artículos, CABYS o precios desde un archivo xlsx/csv",
            description = "Valida el esquema y cada fila, devuelve el resultado por fila y, "
                    + "salvo que simulacion=true, guarda las filas válidas.")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Resultado por fila (simulación o importación aplicada)"),
        @APIResponse(responseCode = "400", description = "Parámetros faltantes, formato o esquema no soportado"),
        @APIResponse(responseCode = "401", description = "No autenticado"),
        @APIResponse(responseCode = "403", description = "Rol admin/registro ausente"),
        @APIResponse(responseCode = "500", description = "Error interno")
    })
    public Response importar(
            @RestForm("entidad") @Nullable String entidad,
            @RestForm("simulacion") @Nullable String simulacion,
            @RestForm("archivo") @Nullable FileUpload archivo) {
        ImportacionMasivaEntidad destino = ImportacionMasivaEntidad.desdeClave(entidad);
        if (destino == null) {
            return badRequest("Falta o es inválido el parámetro 'entidad'. Valores soportados: "
                    + ImportacionMasivaEntidad.clavesDisponibles() + ".");
        }
        if (archivo == null) {
            return badRequest("Falta el archivo en el campo 'archivo'.");
        }
        String nombreArchivo = archivo.fileName() == null ? "" : archivo.fileName();
        byte[] contenido;
        try {
            contenido = Files.readAllBytes(archivo.uploadedFile());
        } catch (IOException e) {
            LOG.warn("No se pudo leer el archivo " + nombreArchivo
                    + " | source=ImportacionMasivaResource.importar() | despues=" + e.getMessage());
            return badRequest("No se pudo leer el archivo enviado: " + e.getMessage());
        }
        if (contenido.length == 0) {
            return badRequest("El archivo está vacío.");
        }

        boolean esSimulacion = simulacion == null || simulacion.isBlank()
                || parsearBooleanoSeguro(simulacion);
        try {
            ImportacionMasivaResultado resultado = importacionMasivaService
                    .importar(destino, nombreArchivo, contenido, esSimulacion);
            LOG.info("Importación masiva " + destino.getClave() + " (" + (esSimulacion ? "simulación" : "aplicada")
                    + "): " + resultado.getMensaje() + " | source=ImportacionMasivaResource.importar()"
                    + " | despues=" + resultado.getMensaje());
            return Response.ok(ApiResponse.ok(resultado)).build();
        } catch (EsquemaInvalidoException e) {
            return badRequest(e.getMessage());
        } catch (RuntimeException e) {
            LOG.warn("Error en la importación masiva " + destino.getClave(), e);
            return serverError("No se pudo completar la importación de " + destino.getEtiqueta() + ".");
        }
    }

    /**
     * GET /api/app/importacion-masiva/esquema — the declared columns of every
     * importable target, so the client can render a form or build a sheet.
     */
    @GET
    @Path("/esquema")
    @Operation(summary = "Esquema de columnas de la importación masiva")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Columnas por entidad"),
        @APIResponse(responseCode = "401", description = "No autenticado"),
        @APIResponse(responseCode = "403", description = "Rol admin/registro ausente")
    })
    public Response esquema(@QueryParam("entidad") @Nullable String entidad) {
        if (entidad == null || entidad.isBlank()) {
            Map<String, Object> todas = new LinkedHashMap<>();
            for (ImportacionMasivaEntidad destino : ImportacionMasivaEntidad.values()) {
                todas.put(destino.getClave(), importacionMasivaService.columnas(destino));
            }
            return Response.ok(ApiResponse.ok(todas)).build();
        }
        ImportacionMasivaEntidad destino = ImportacionMasivaEntidad.desdeClave(entidad);
        if (destino == null) {
            return badRequest("Entidad no soportada: " + entidad + ". Valores soportados: "
                    + ImportacionMasivaEntidad.clavesDisponibles() + ".");
        }
        return Response.ok(ApiResponse.ok(importacionMasivaService.columnas(destino))).build();
    }

    /**
     * GET /api/app/importacion-masiva/plantilla?entidad=&amp;formato= — the
     * template as an attachment download, the inverse of
     * {@code POST /api/app/export}. Unknown entities answer 404; an unsupported
     * format answers 400.
     */
    @GET
    @Path("/plantilla")
    @Operation(summary = "Descargar la plantilla de importación (xlsx/csv)")
    @APIResponses({
        @APIResponse(responseCode = "200",
                description = "Plantilla descargada",
                content = @Content(mediaType = MediaType.APPLICATION_OCTET_STREAM)),
        @APIResponse(responseCode = "400", description = "Formato no soportado"),
        @APIResponse(responseCode = "401", description = "No autenticado"),
        @APIResponse(responseCode = "403", description = "Rol admin/registro ausente"),
        @APIResponse(responseCode = "404", description = "Entidad no soportada"),
        @APIResponse(responseCode = "500", description = "Error interno")
    })
    public Response plantilla(
            @QueryParam("entidad") @Nullable String entidad,
            @QueryParam("formato") @DefaultValue(FORMATO_XLSX) @Nullable String formato) {

        ImportacionMasivaEntidad destino = ImportacionMasivaEntidad.desdeClave(entidad);
        if (destino == null) {
            return notFound("Entidad no soportada: " + entidad + ". Valores soportados: "
                    + ImportacionMasivaEntidad.clavesDisponibles() + ".");
        }
        String tipo = formato == null || formato.isBlank()
                ? FORMATO_XLSX : formato.trim().toLowerCase(Locale.ROOT);
        String nombreArchivo = "plantilla-" + destino.getClave();
        try {
            if (FORMATO_CSV.equals(tipo)) {
                byte[] bytes = importacionMasivaService.plantillaCsv(destino)
                        .getBytes(StandardCharsets.UTF_8);
                nombreArchivo = nombreArchivo + ".csv";
                return adjunto(bytes, nombreArchivo, "text/csv; charset=UTF-8");
            }
            if (!FORMATO_XLSX.equals(tipo)) {
                return badRequest("Tipo de plantilla no soportado: " + tipo + ". Valores: xlsx|csv.");
            }
            return adjunto(importacionMasivaService.plantillaXlsx(destino), nombreArchivo + ".xlsx",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        } catch (IOException e) {
            LOG.warn("Error generando la plantilla de " + destino.getClave(), e);
            return serverError("No se pudo generar la plantilla de importación.");
        }
    }

    /** HEAD is derived from the GET above by the JAX-RS runtime. */

    // ── Helpers ───────────────────────────────────────────────────────────────

    @Nonnull
    private static Response adjunto(@Nonnull byte[] bytes, @Nonnull String nombreArchivo,
                                    @Nonnull String contentType) {
        return Response.ok(bytes)
                .type(contentType)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + nombreArchivo + "\"")
                .build();
    }

    /** Treats anything other than a recognised false-token as {@code true}. */
    private static boolean parsearBooleanoSeguro(@Nonnull String valor) {
        String v = valor.trim().toLowerCase(Locale.ROOT);
        return !("false".equals(v) || "0".equals(v) || "no".equals(v));
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
}
