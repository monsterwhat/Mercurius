package Services;

import Models.AppSettings;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.zip.GZIPOutputStream;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@Named
@ApplicationScoped
public class BackupService implements Serializable {

    private static final long serialVersionUID = 1L;
    private static final String BACKUP_PREFIX = "mercurius_";
    private static final String BACKUP_SUFFIX = ".sql.gz";
    private static final DateTimeFormatter FILENAME_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    @Inject
    private @Nonnull AppSettingsService appSettingsService;

    @Inject
    private @Nonnull AlertasService alertasService;

    @ConfigProperty(name = "quarkus.datasource.jdbc.url")
    @Nonnull String jdbcUrl;

    @ConfigProperty(name = "quarkus.datasource.username")
    @Nonnull String dbUser;

    @ConfigProperty(name = "quarkus.datasource.password")
    @Nonnull String dbPass;

    /**
     * Override de despliegue: además de la propiedad, MicroProfile Config
     * mapea automáticamente la variable de entorno BACKUP_PGDUMP_PATH.
     */
    @ConfigProperty(name = "backup.pgdump.path", defaultValue = "C:/Program Files/PostgreSQL/18/bin/pg_dump.exe")
    @Nonnull String pgDumpPath;

    private String resolvedPgDump;

    public boolean ejecutarBackup() {
        try {
            AppSettings settings = appSettingsService.findOrCreateCurrent();
            if (settings == null) {
                alertasService.registrarAlerta("Error", "No se encontró configuración activa para ejecutar backup", null, 0, "BackupService.ejecutarBackup()", null, null);
                return false;
            }

            String backupRuta = settings.getBackupRuta();
            if (backupRuta == null || backupRuta.isBlank()) {
                backupRuta = getDefaultBackupPath();
                settings.setBackupRuta(backupRuta);
                appSettingsService.update(settings);
            }

            Path backupDir = Paths.get(backupRuta);
            Files.createDirectories(backupDir);

            DbConnectionInfo dbInfo = parseJdbcUrl(jdbcUrl);
            String timestamp = LocalDateTime.now().format(FILENAME_FORMATTER);
            String filename = BACKUP_PREFIX + timestamp + BACKUP_SUFFIX;
            Path outputFile = backupDir.resolve(filename);

            ProcessBuilder pb = new ProcessBuilder(buildDumpCommand(resolvePgDump(), dbInfo, dbUser));
            pb.redirectErrorStream(true);
            // Seguridad: la contraseña NUNCA va en la línea de comandos (visible
            // en la lista de procesos del SO, como ocurría con "-p<pass>" de
            // mysqldump). Viaja solo en el entorno del proceso hijo y no se
            // registra en alertas ni logs.
            pb.environment().put("PGPASSWORD", dbPass);

            Process process = pb.start();
            int exitCode;
            try (InputStream is = process.getInputStream();
                 FileOutputStream fos = new FileOutputStream(outputFile.toFile());
                 GZIPOutputStream gzos = new GZIPOutputStream(fos)) {
                is.transferTo(gzos);
                gzos.finish();
            }
            exitCode = process.waitFor();

            if (exitCode == 0) {
                settings.setBackupUltimoEjecutado(LocalDateTime.now());
                appSettingsService.update(settings);

                alertasService.registrarAlerta("Backup", "Backup completado: " + filename
                    + " (" + getTamanioBackup(outputFile.toFile()) + ")", null, 0,
                    "BackupService.ejecutarBackup()", null, null);

                limpiarBackupsViejos();
                return true;
            } else {
                alertasService.registrarAlerta("Error", "pg_dump falló con código: " + exitCode, null, 0,
                    "BackupService.ejecutarBackup()", null, null);
                try { Files.deleteIfExists(outputFile); } catch (IOException ignored) {}
                return false;
            }

        } catch (IOException e) {
            alertasService.registrarAlerta("Error", "Error de E/S en backup: " + e.getMessage(), null, 0,
                "BackupService.ejecutarBackup()", null, e.getMessage());
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            alertasService.registrarAlerta("Error", "Backup interrumpido: " + e.getMessage(), null, 0,
                "BackupService.ejecutarBackup()", null, e.getMessage());
            return false;
        }
    }

    public void limpiarBackupsViejos() {
        try {
            AppSettings settings = appSettingsService.findOrCreateCurrent();
            if (settings == null) return;

            String backupRuta = settings.getBackupRuta();
            if (backupRuta == null || backupRuta.isBlank()) return;

            Integer retencionDias = settings.getBackupRetencionDias();
            if (retencionDias == null || retencionDias <= 0) {
                retencionDias = 7;
            }

            Path backupDir = Paths.get(backupRuta);
            if (!Files.exists(backupDir)) return;

            List<Path> backupFiles = new ArrayList<>();
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(backupDir, BACKUP_PREFIX + "*" + BACKUP_SUFFIX)) {
                for (Path entry : stream) {
                    backupFiles.add(entry);
                }
            }

            for (Path file : backupFiles) {
                BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class);
                long age = System.currentTimeMillis() - attrs.lastModifiedTime().toMillis();
                long ageDays = age / (24L * 60 * 60 * 1000);
                if (ageDays >= retencionDias) {
                    Files.delete(file);
                    alertasService.registrarAlerta("Backup", "Backup viejo eliminado: " + file.getFileName(), null, 0,
                        "BackupService.limpiarBackupsViejos()", null, null);
                }
            }

        } catch (IOException e) {
            alertasService.registrarAlerta("Error", "Error limpiando backups viejos: " + e.getMessage(), null, 0,
                "BackupService.limpiarBackupsViejos()", null, e.getMessage());
        }
    }

    public @Nonnull List<String> listarBackups() {
        List<String> result = new ArrayList<>();
        try {
            AppSettings settings = appSettingsService.findOrCreateCurrent();
            if (settings == null) return result;

            String backupRuta = settings.getBackupRuta();
            if (backupRuta == null || backupRuta.isBlank()) return result;

            Path backupDir = Paths.get(backupRuta);
            if (!Files.exists(backupDir)) return result;

            List<Path> backupFiles = new ArrayList<>();
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(backupDir, BACKUP_PREFIX + "*" + BACKUP_SUFFIX)) {
                for (Path entry : stream) {
                    backupFiles.add(entry);
                }
            }

            backupFiles.sort(Collections.reverseOrder(Comparator.comparingLong(p -> {
                try { return p.toFile().lastModified(); } catch (RuntimeException e) { return 0L; }
            })));

            for (Path file : backupFiles) {
                String size = getTamanioBackup(file.toFile());
                result.add(file.getFileName().toString() + "|" + size);
            }

        } catch (IOException e) {
            alertasService.registrarAlerta("Error", "Error listando backups: " + e.getMessage(), null, 0,
                "BackupService.listarBackups()", null, e.getMessage());
        }
        return result;
    }

    public @Nonnull String getTamanioBackup(@Nonnull String filename) {
        try {
            AppSettings settings = appSettingsService.findOrCreateCurrent();
            if (settings == null) return "0 B";

            String backupRuta = settings.getBackupRuta();
            if (backupRuta == null || backupRuta.isBlank()) return "0 B";

            Path file = Paths.get(backupRuta, filename);
            if (Files.exists(file)) {
                return getTamanioBackup(file.toFile());
            }
        } catch (RuntimeException e) {
            return "0 B";
        }
        return "0 B";
    }

    public @Nullable Path getBackupFilePath(@Nonnull String filename) {
        try {
            AppSettings settings = appSettingsService.findOrCreateCurrent();
            if (settings == null) return null;
            String backupRuta = settings.getBackupRuta();
            if (backupRuta == null || backupRuta.isBlank()) return null;
            Path file = Paths.get(backupRuta, filename);
            if (Files.exists(file)) {
                return file;
            }
        } catch (RuntimeException e) {
            return null;
        }
        return null;
    }

    public @Nullable AppSettings getSettings() {
        return appSettingsService.findOrCreateCurrent();
    }

    public void saveSettings(@Nonnull AppSettings settings) {
        appSettingsService.update(settings);
    }

    private String getTamanioBackup(File file) {
        long bytes = file.length();
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024));
    }

    private String getDefaultBackupPath() {
        String mainDir;
        try {
            javax.swing.filechooser.FileSystemView fsv = javax.swing.filechooser.FileSystemView.getFileSystemView();
            mainDir = fsv.getDefaultDirectory().getAbsolutePath();
        } catch (Exception e) {
            mainDir = System.getProperty("user.home") + File.separator + "Documents";
        }

        AppSettings settings = appSettingsService.findOrCreateCurrent();
        String profileName = (settings != null && settings.getNombrePerfil() != null)
            ? settings.getNombrePerfil() : "default";

        return mainDir + File.separator + "Mercurius" + File.separator + profileName + File.separator + "backups";
    }

    private DbConnectionInfo parseJdbcUrl(String url) {
        DbConnectionInfo info = new DbConnectionInfo();
        info.host = "localhost";
        info.dbName = "mercurius";

        if (url == null || url.isBlank()) return info;

        try {
            String jdbcPart = url;
            if (jdbcPart.startsWith("jdbc:")) {
                jdbcPart = jdbcPart.substring(5);
            }

            URI uri = new URI(jdbcPart);
            info.host = uri.getHost() != null ? uri.getHost() : "localhost";
            // Fallback al puerto por defecto de PostgreSQL: el antiguo 3306 era
            // ignorado por mysqldump, pero pg_dump sí conecta con este valor.
            info.port = uri.getPort() > 0 ? uri.getPort() : 5432;

            String path = uri.getPath();
            if (path != null) {
                path = path.replaceAll("[?;].*$", "");
                if (path.startsWith("/")) {
                    path = path.substring(1);
                }
                if (!path.isBlank()) {
                    info.dbName = path;
                }
            }

        } catch (URISyntaxException e) {
            alertasService.registrarAlerta("Advertencia", "No se pudo parsear JDBC URL: " + e.getMessage(), null, 0,
                "BackupService.parseJdbcUrl()", null, null);
        }

        return info;
    }

    // Package-private solo para las pruebas unitarias del mismo paquete;
    // no relajar más allá de esto.
    static class DbConnectionInfo {
        String host;
        int port;
        String dbName;
    }

    /**
     * Construye el comando pg_dump (formato SQL plano) para un backup.
     * Package-private y estático solo para poder probarse en unidad sin
     * levantar Quarkus.
     *
     * Mapeo desde el comando mysqldump anterior:
     * - "-u&lt;user&gt;"          → "-U &lt;user&gt;"
     * - "-p&lt;pass&gt;"          → eliminado: la contraseña viaja por la variable
     *                          de entorno PGPASSWORD (nunca en la línea de
     *                          comandos)
     * - "-h&lt;host&gt;"          → "-h &lt;host&gt;"
     * - (puerto implícito)   → "-p &lt;port&gt;" (pg_dump sí lo necesita)
     * - "&lt;dbname&gt;"           → "-d &lt;dbname&gt;"
     * - "--routines"         → sin equivalente necesario: pg_dump incluye las
     *                          rutinas por defecto (sección post-data)
     * - "--triggers"         → sin equivalente necesario: pg_dump incluye los
     *                          triggers por defecto
     * - "--add-drop-database" → "--clean" (DROP antes de CREATE al restaurar)
     *
     * "-F p" fuerza SQL plano y "-f -" lo dirige a stdout, que ejecutarBackup()
     * comprime en gzip hacia el archivo final manteniendo la convención
     * mercurius_*.sql.gz que consumen BackupController y listarBackups().
     */
    static List<String> buildDumpCommand(@Nonnull String pgDumpPath,
                                         @Nonnull DbConnectionInfo dbInfo,
                                         @Nonnull String dbUser) {
        return List.of(
            pgDumpPath,
            "-h", dbInfo.host,
            "-p", String.valueOf(dbInfo.port),
            "-U", dbUser,
            "-d", dbInfo.dbName,
            "-F", "p",
            "-f", "-",
            "--clean"
        );
    }

    private String resolvePgDump() {
        if (resolvedPgDump != null) {
            return resolvedPgDump;
        }

        // 1) Override explícito de despliegue (backup.pgdump.path /
        //    BACKUP_PGDUMP_PATH). Solo se acepta si el binario existe en disco;
        //    si no, se continúa con la búsqueda en PATH como hacía la
        //    resolución de mysqldump.
        if (pgDumpPath != null && !pgDumpPath.isBlank()
                && Files.exists(Paths.get(pgDumpPath))) {
            resolvedPgDump = pgDumpPath;
            return resolvedPgDump;
        }

        // 2) Búsqueda en PATH (misma estrategia que resolveMysqldump()).
        //    Ignorar el fallo aquí es intencional: solo significa que pg_dump
        //    no está en PATH y hay que seguir con los candidatos conocidos.
        try {
            ProcessBuilder test = new ProcessBuilder("pg_dump", "--version");
            test.redirectErrorStream(true);
            Process p = test.start();
            int code = p.waitFor();
            p.getInputStream().close();
            if (code == 0) {
                resolvedPgDump = "pg_dump";
                return resolvedPgDump;
            }
        } catch (Exception ignored) { /* justificado arriba: sondeo de PATH */ }

        String os = System.getProperty("os.name", "").toLowerCase();
        List<String> candidates = new ArrayList<>();

        if (os.contains("win")) {
            String progFiles = System.getenv("ProgramFiles");
            String progFilesX86 = System.getenv("ProgramFiles(x86)");
            String[] roots = { progFiles, progFilesX86 };
            for (String root : roots) {
                if (root == null) continue;
                candidates.add(root + "\\PostgreSQL\\18\\bin\\pg_dump.exe");
                candidates.add(root + "\\PostgreSQL\\17\\bin\\pg_dump.exe");
                candidates.add(root + "\\PostgreSQL\\16\\bin\\pg_dump.exe");
                candidates.add(root + "\\PostgreSQL\\15\\bin\\pg_dump.exe");
            }
        } else {
            candidates.add("/usr/bin/pg_dump");
            candidates.add("/usr/local/bin/pg_dump");
            candidates.add("/opt/homebrew/bin/pg_dump");
            candidates.add("/usr/lib/postgresql/18/bin/pg_dump");
            candidates.add("/usr/lib/postgresql/16/bin/pg_dump");
        }

        for (String candidate : candidates) {
            if (Files.exists(Paths.get(candidate))) {
                resolvedPgDump = candidate;
                return resolvedPgDump;
            }
        }

        // 4) Fallback final: la ruta por defecto de PostgreSQL 18 configurada
        //    en pgDumpPath (equivalente al fallback desnudo "mysqldump").
        resolvedPgDump = pgDumpPath;
        return resolvedPgDump;
    }
}
