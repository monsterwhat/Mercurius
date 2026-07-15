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

    private String resolvedMysqldump;

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

            ProcessBuilder pb = new ProcessBuilder(
                resolveMysqldump(),
                "-u" + dbUser,
                "-p" + dbPass,
                "-h" + dbInfo.host,
                dbInfo.dbName,
                "--routines",
                "--triggers",
                "--add-drop-database"
            );
            pb.redirectErrorStream(true);

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
                alertasService.registrarAlerta("Error", "mysqldump falló con código: " + exitCode, null, 0,
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
            info.port = uri.getPort() > 0 ? uri.getPort() : 3306;

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

    private static class DbConnectionInfo {
        String host;
        int port;
        String dbName;
    }

    private String resolveMysqldump() {
        if (resolvedMysqldump != null) {
            return resolvedMysqldump;
        }

        try {
            ProcessBuilder test = new ProcessBuilder("mysqldump", "--version");
            test.redirectErrorStream(true);
            Process p = test.start();
            int code = p.waitFor();
            p.getInputStream().close();
            if (code == 0) {
                resolvedMysqldump = "mysqldump";
                return resolvedMysqldump;
            }
        } catch (Exception ignored) {}

        String os = System.getProperty("os.name", "").toLowerCase();
        List<String> candidates = new ArrayList<>();

        if (os.contains("win")) {
            String progFiles = System.getenv("ProgramFiles");
            String progFilesX86 = System.getenv("ProgramFiles(x86)");
            String[] roots = { progFiles, progFilesX86 };
            for (String root : roots) {
                if (root == null) continue;
                candidates.add(root + "\\MySQL\\MySQL Server 8.0\\bin\\mysqldump.exe");
                candidates.add(root + "\\MySQL\\MySQL Server 8.4\\bin\\mysqldump.exe");
                candidates.add(root + "\\MySQL\\MySQL Server 9.0\\bin\\mysqldump.exe");
                candidates.add(root + "\\MySQL\\MySQL Workbench 8.0\\mysqldump.exe");
            }
            candidates.add("C:\\mysql\\bin\\mysqldump.exe");
        } else {
            candidates.add("/usr/bin/mysqldump");
            candidates.add("/usr/local/bin/mysqldump");
            candidates.add("/opt/homebrew/bin/mysqldump");
            candidates.add("/usr/local/mysql/bin/mysqldump");
        }

        for (String candidate : candidates) {
            if (Files.exists(Paths.get(candidate))) {
                resolvedMysqldump = candidate;
                return resolvedMysqldump;
            }
        }

        resolvedMysqldump = "mysqldump";
        return resolvedMysqldump;
    }
}
