package Controllers.Api.App;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.annotation.security.RolesAllowed;

import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.RestForm;
import org.jboss.resteasy.reactive.multipart.FileUpload;

import Models.DTO.ApiResponse;
import Services.HistoricoEmitidosService;
import io.quarkus.security.identity.SecurityIdentity;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * Carga del histórico de comprobantes EMITIDOS (migración desde el sistema
 * FoxPro "vsuper" y desde los XML firmados de Hacienda).
 *
 * <p>Complementa a {@link FacturasRecibidasResource} (que es sólo de compras):
 * aquí se reconstruyen los documentos que la empresa emitió, con su estado
 * real ante Hacienda, <b>sin volver a enviarlos a Hacienda</b>.</p>
 *
 * <p>Endpoints:</p>
 * <ul>
 *   <li>{@code POST /api/app/historico-emitidos/upload} — multipart
 *       ({@code files[]} + {@code estado} + opcional {@code fechaEnvio}).</li>
 *   <li>{@code POST /api/app/historico-emitidos/mensaje-hacienda} — aplica los
 *       Mensaje Hacienda (aceptado/rechazado) sobre comprobantes ya cargados.</li>
 *   <li>{@code GET /api/app/historico-emitidos/resumen} — estado de la carga.</li>
 * </ul>
 *
 * <p><b>Autorización:</b> sólo {@code admin} (migración masiva y sensible).</p>
 */
@Path("/api/app/historico-emitidos")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed({"admin"})
@Tag(name = "App - Histórico Emitidos")
@RequestScoped
public class HistoricoEmitidosResource {

    private static final Logger LOG = Logger.getLogger(HistoricoEmitidosResource.class);

    @Inject
    HistoricoEmitidosService historicoEmitidosService;

    @Inject
    SecurityIdentity identity;

    /**
     * Resultado por archivo.
     *
     * @param fileName nombre del archivo
     * @param exito   true si el archivo quedó importado o ya existía
     * @param mensaje detalle legible
     */
    public record ResultadoArchivo(String fileName, boolean exito, String mensaje) { }

    /**
     * Resumen de una carga.
     *
     * @param resultados detalle por archivo
     * @param procesados archivos importados o ya presentes
     * @param fallidos   archivos con error
     */
    public record ResumenCarga(List<ResultadoArchivo> resultados, long procesados, long fallidos) { }

    /**
     * POST /upload — carga masiva de comprobantes emitidos históricos.
     *
     * @param files      archivos XML de los comprobantes
     * @param estado     estado real ante Hacienda (obligatorio; no PENDIENTE)
     * @param fechaEnvio fecha original de envío (ISO-8601, opcional)
     */
    @POST
    @Path("/upload")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Operation(summary = "Cargar comprobantes emitidos históricos (XML) sin reenviar a Hacienda")
    public Response upload(@RestForm("files") @Nullable List<FileUpload> files,
                           @RestForm("estado") @Nullable String estado,
                           @RestForm("fechaEnvio") @Nullable String fechaEnvio) {
        List<FileUpload> partes = files == null ? List.of() : files;
        if (partes.isEmpty()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(ApiResponse.error("VALIDATION_ERROR", "No se recibió ningún archivo para procesar"))
                    .build();
        }
        if (estado == null || estado.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(ApiResponse.error("VALIDATION_ERROR",
                            "El estado es obligatorio (ACEPTADO/RECHAZADO). No se admite PENDIENTE: "
                                    + "un histórico pendiente sería reenviado a Hacienda por el scheduler."))
                    .build();
        }
        LocalDateTime fecha = null;
        if (fechaEnvio != null && !fechaEnvio.isBlank()) {
            try {
                fecha = LocalDateTime.parse(fechaEnvio.trim());
            } catch (RuntimeException e) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(ApiResponse.error("VALIDATION_ERROR",
                                "fechaEnvio inválida (se espera ISO-8601, p.ej. 2026-08-24T16:49:18)"))
                        .build();
            }
        }

        String username = principalUsername();
        List<ResultadoArchivo> resultados = new ArrayList<>();
        for (FileUpload parte : partes) {
            resultados.add(procesar(parte, estado.trim().toUpperCase(), fecha, username));
        }
        long ok = resultados.stream().filter(ResultadoArchivo::exito).count();
        ResumenCarga payload = new ResumenCarga(resultados, ok, resultados.size() - ok);
        return Response.ok(ApiResponse.ok(payload)).build();
    }

    private @Nonnull ResultadoArchivo procesar(@Nonnull FileUpload parte, @Nonnull String estado,
                                               @Nullable LocalDateTime fecha, @Nonnull String username) {
        String fileName = parte.fileName() == null ? "(sin nombre)" : parte.fileName();
        byte[] contenido;
        try {
            contenido = Files.readAllBytes(parte.uploadedFile());
        } catch (IOException e) {
            LOG.warn("Archivo: " + fileName + " - Error: " + e.getMessage()
                    + " | source=HistoricoEmitidosResource.procesar() | antes=" + fileName
                    + " | despues=" + e.getMessage());
            return new ResultadoArchivo(fileName, false, "No se pudo leer el archivo: " + e.getMessage());
        }
        if (contenido.length == 0) {
            return new ResultadoArchivo(fileName, false, "El archivo está vacío");
        }
        try {
            HistoricoEmitidosService.Resultado r = historicoEmitidosService.importar(
                    new String(contenido, StandardCharsets.UTF_8), estado, fecha, username);
            return new ResultadoArchivo(fileName, true, r.mensaje());
        } catch (IllegalArgumentException | IllegalStateException e) {
            LOG.warn("Archivo: " + fileName + " - Error: " + e.getMessage()
                    + " | user=" + username + " | source=HistoricoEmitidosResource.procesar()"
                    + " | antes=" + fileName + " | despues=" + e.getMessage());
            return new ResultadoArchivo(fileName, false, e.getMessage());
        } catch (RuntimeException e) {
            LOG.error("Error importando el archivo " + fileName, e);
            return new ResultadoArchivo(fileName, false, "Error inesperado: " + e.getMessage());
        }
    }

    /**
     * POST /mensaje-hacienda — aplica la respuesta de Hacienda (Mensaje
     * Hacienda) sobre los comprobantes ya cargados.
     *
     * @param files archivos Mensaje Hacienda (XML)
     */
    @POST
    @Path("/mensaje-hacienda")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Operation(summary = "Aplicar Mensaje Hacienda (aceptado/rechazado) sobre comprobantes cargados")
    public Response aplicarMensaje(@RestForm("files") @Nullable List<FileUpload> files) {
        List<FileUpload> partes = files == null ? List.of() : files;
        if (partes.isEmpty()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(ApiResponse.error("VALIDATION_ERROR", "No se recibió ningún archivo"))
                    .build();
        }
        String username = principalUsername();
        List<ResultadoArchivo> resultados = new ArrayList<>();
        for (FileUpload parte : partes) {
            String fileName = parte.fileName() == null ? "(sin nombre)" : parte.fileName();
            try {
                byte[] contenido = Files.readAllBytes(parte.uploadedFile());
                String clave = historicoEmitidosService.aplicarMensajeHacienda(
                        new String(contenido, StandardCharsets.UTF_8), username);
                resultados.add(new ResultadoArchivo(fileName, true, "Estado aplicado a " + clave));
            } catch (IOException | RuntimeException e) {
                LOG.warn("Archivo: " + fileName + " - Error: " + e.getMessage()
                        + " | user=" + username + " | source=HistoricoEmitidosResource.aplicarMensaje()"
                        + " | antes=" + fileName + " | despues=" + e.getMessage());
                resultados.add(new ResultadoArchivo(fileName, false, String.valueOf(e.getMessage())));
            }
        }
        long ok = resultados.stream().filter(ResultadoArchivo::exito).count();
        return Response.ok(ApiResponse.ok(new ResumenCarga(resultados, ok, resultados.size() - ok))).build();
    }

    /**
     * GET /resumen — estado actual de la carga (totales del histórico).
     */
    @GET
    @Path("/resumen")
    @Operation(summary = "Resumen de la carga del histórico de comprobantes emitidos")
    public Response resumen() {
        return Response.ok(ApiResponse.ok(historicoEmitidosService.resumen())).build();
    }

    private String principalUsername() {
        try {
            if (!identity.isAnonymous() && identity.getPrincipal() != null
                    && identity.getPrincipal().getName() != null) {
                return identity.getPrincipal().getName();
            }
        } catch (RuntimeException e) {
            LOG.debug("No principal name resolvable", e);
        }
        return "system";
    }
}
