package Controllers.Api.App;

import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import jakarta.annotation.Nullable;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.jboss.logging.Logger;

/**
 * Navbar routes rendered through the shared Qute layout (T11): the reportes hub
 * and the log-activities placeholder. All render {@code layout.html} so they get
 * the role-gated navbar, web-bundler tags and CSRF headers like every /app page.
 */
@Path("/app")
@Produces(MediaType.TEXT_HTML)
public class MiscPagesResource {

    private static final Logger LOG = Logger.getLogger(MiscPagesResource.class);

    @Inject
    @Location("pages/reportes/index")
    Template reportesPage;

    @Inject
    @Location("pages/registros/log")
    Template logPage;

    @GET @Path("/reportes") @RolesAllowed({"admin","registro","inventario","tributacion"})
    public Response reportes() {
        return Response.ok(reportesPage.instance().render())
                .type(MediaType.TEXT_HTML_TYPE.withCharset("UTF-8")).build();
    }

    @GET @Path("/registros/log") @RolesAllowed({"admin","registro"})
    public Response registrosLog() {
        try {
            List<Map<String, Object>> archivos = new ArrayList<>();
            java.nio.file.Path dir = Paths.get("logs").toAbsolutePath().normalize();
            if (Files.isDirectory(dir)) {
                try (Stream<java.nio.file.Path> listado = Files.list(dir)) {
                    List<java.nio.file.Path> logs = new ArrayList<>();
                    listado.filter(p -> p.getFileName().toString().startsWith("mercurius.log"))
                            .forEach(logs::add);
                    logs.sort(Comparator.comparingLong((java.nio.file.Path p) -> {
                        try {
                            return Files.getLastModifiedTime(p).toMillis();
                        } catch (java.io.IOException e) {
                            return 0L;
                        }
                    }).reversed());
                    DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
                    for (java.nio.file.Path log : logs) {
                        Map<String, Object> fila = new LinkedHashMap<>();
                        fila.put("nombre", log.getFileName().toString());
                        fila.put("tamanio", formatoTamanio(safeSize(log)));
                        fila.put("modificado", fmt.format(Files.getLastModifiedTime(log).toInstant()
                                .atZone(ZoneId.systemDefault()).toLocalDateTime()));
                        archivos.add(fila);
                    }
                }
            }
            String html = logPage.data("archivos", archivos).data("totalArchivos", archivos.size()).render();
            return Response.ok(html)
                    .type(MediaType.TEXT_HTML_TYPE.withCharset("UTF-8")).build();
        } catch (RuntimeException | java.io.IOException e) {
            LOG.warn("Error listando archivos de log", e);
            return Response.serverError().build();
        }
    }

    @GET @Path("/registros/log/descargar") @RolesAllowed({"admin","registro"})
    @Produces(MediaType.APPLICATION_OCTET_STREAM)
    public Response descargarLog(@QueryParam("archivo") @Nullable String archivo) {
        try {
            if (archivo == null || archivo.isBlank()) {
                return Response.status(Response.Status.NOT_FOUND).build();
            }
            String nombre = archivo.trim();
            if (!nombre.startsWith("mercurius.log") || nombre.contains("/") || nombre.contains("\\")) {
                return Response.status(Response.Status.NOT_FOUND).build();
            }
            java.nio.file.Path base = Paths.get("logs").toAbsolutePath().normalize();
            java.nio.file.Path file = base.resolve(nombre).normalize();
            if (!file.startsWith(base) || !Files.isRegularFile(file)) {
                return Response.status(Response.Status.NOT_FOUND).build();
            }
            String tipo = nombre.endsWith(".gz") ? "application/gzip" : "text/plain";
            return Response.ok(file.toFile())
                    .type(tipo)
                    .header("Content-Disposition", "attachment; filename=\"" + nombre + "\"")
                    .build();
        } catch (RuntimeException e) {
            LOG.warn("Error descargando archivo de log", e);
            return Response.serverError().build();
        }
    }

    private static long safeSize(java.nio.file.Path file) {
        try {
            return Files.size(file);
        } catch (java.io.IOException e) {
            return 0L;
        }
    }

    private static String formatoTamanio(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return (bytes / 1024) + " KB";
        }
        return String.format(java.util.Locale.ROOT, "%.1f MB", bytes / 1048576.0);
    }
}
