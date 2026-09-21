package Controllers.Api.App;

import Models.AppSettings;
import Services.BackupService;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jboss.logging.Logger;

@Path("/app/backups")
@Produces(MediaType.TEXT_HTML)
@RolesAllowed("admin")
public class BackupsPagesResource {
    private static final Logger LOG = Logger.getLogger(BackupsPagesResource.class);

    @Inject
    @Nonnull
    BackupService backupService;

    @Inject
    @Nonnull
    @Location("pages/backups/index")
    Template page;

    @GET
    public Response index() {
        return pageOk(null, null);
    }

    @POST
    @Path("/crear")
    public Response crear() {
        try {
            boolean ok = backupService.ejecutarBackup();
            if (ok) {
                return pageOk("success", "Respaldo creado correctamente.");
            }
            return pageOk("error", "No se pudo crear el respaldo; revise logs/mercurius.log.");
        } catch (RuntimeException e) {
            LOG.warn("Error creando respaldo manual", e);
            return pageOk("error", "No se pudo crear el respaldo: " + e.getMessage());
        }
    }

    @GET
    @Path("/descargar")
    @Produces("application/gzip")
    public Response descargar(@QueryParam("archivo") @Nullable String archivo) {
        java.nio.file.Path file = resolvedBackup(archivo);
        if (file == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        return Response.ok(file.toFile())
                .type("application/gzip")
                .header("Content-Disposition",
                        "attachment; filename=\"" + file.getFileName().toString() + "\"")
                .build();
    }

    private Response pageOk(@Nullable String estado, @Nullable String mensaje) {
        try {
            List<Map<String, Object>> filas = new ArrayList<>();
            for (String entrada : backupService.listarBackups()) {
                String[] partes = entrada.split("\\|", 2);
                Map<String, Object> fila = new LinkedHashMap<>();
                fila.put("nombre", partes[0]);
                fila.put("tamanio", partes.length > 1 ? partes[1] : "");
                filas.add(fila);
            }
            TemplateInstance instance = page.instance()
                    .data("backups", filas)
                    .data("totalBackups", filas.size())
                    .data("estado", estado)
                    .data("mensaje", mensaje);
            return Response.ok(instance.render())
                    .type(MediaType.TEXT_HTML_TYPE.withCharset("UTF-8")).build();
        } catch (RuntimeException e) {
            LOG.warn("Error renderizando la página de backups", e);
            return Response.serverError().build();
        }
    }

    @Nullable
    private java.nio.file.Path resolvedBackup(@Nullable String archivo) {
        try {
            if (archivo == null || archivo.isBlank()) {
                return null;
            }
            String nombre = archivo.trim();
            if (!nombre.endsWith(".sql.gz") || nombre.contains("/") || nombre.contains("\\")) {
                return null;
            }
            AppSettings settings = backupService.getSettings();
            if (settings == null || settings.getBackupRuta() == null
                    || settings.getBackupRuta().isBlank()) {
                return null;
            }
            java.nio.file.Path base = Paths.get(settings.getBackupRuta()).toAbsolutePath().normalize();
            java.nio.file.Path file = base.resolve(nombre).normalize();
            if (!file.startsWith(base) || !Files.isRegularFile(file)) {
                return null;
            }
            return file;
        } catch (RuntimeException e) {
            LOG.warn("Error resolviendo archivo de respaldo", e);
            return null;
        }
    }
}
