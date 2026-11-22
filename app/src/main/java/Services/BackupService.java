package Services;

import Models.ConfiguracionAplicacion;
import org.jboss.logging.Logger;
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
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@Named
@ApplicationScoped
public class BackupService implements Serializable {

    private static final Logger LOG = Logger.getLogger(BackupService.class);

    private static final long serialVersionUID = 1L;
    private static final String BACKUP_PREFIX = "mercurius_";
    private static final String BACKUP_SUFFIX = ".sql.gz";
    private static final DateTimeFormatter FILENAME_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    @Inject
    private @Nonnull AppSettingsService appSettingsService;

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

    /** Espacio libre minimo antes de iniciar un dump (50 MB por defecto). */
    @ConfigProperty(name = "mercurius.backup.espacio-minimo-bytes", defaultValue = "52428800")
    long espacioMinimoBytes;

    /** Dias tras los cuales un backup se considera vencido para el monitoreo. */
    @ConfigProperty(name = "mercurius.backup.edad-maxima-dias", defaultValue = "2")
    int edadMaximaDias;

    private String resolvedPgDump;

    /**
     * Ejecuta un respaldo completo.
     *
     * <p><b>Serializado a propósito.</b> Antes existían dos schedulers
     * (ProgramadorTareas y BackupScheduler) que disparaban a la misma hora y
     * escribían el mismo archivo; el gzip resultante quedaba corrupto. El
     * segundo scheduler se eliminó, y este lock cubre además un respaldo
     * manual concurrente. El nombre temporal lleva un UUID para que dos
     * ejecuciones no puedan compartir el mismo {@code .part} aunque caigan en
     * el mismo segundo.</p>
     */
    public synchronized boolean ejecutarBackup() {
        Path temporal = null;
        Path finalFile = null;
        try {
            ConfiguracionAplicacion settings = appSettingsService.findOrCreateCurrent();
            if (settings == null) {
                LOG.warn("No se encontró configuración activa para ejecutar backup"
                        + " | source=BackupService.ejecutarBackup()");
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
            validarEspacio(backupDir);

            DbConnectionInfo dbInfo = parseJdbcUrl(jdbcUrl);
            String timestamp = LocalDateTime.now().format(FILENAME_FORMATTER);
            String filename = BACKUP_PREFIX + timestamp + BACKUP_SUFFIX;
            finalFile = backupDir.resolve(filename);
            // El dump se escribe en un .part unico y solo se publica cuando la
            // verificacion de gzip + checksum pasa. Un archivo parcial nunca
            // llega a tener el nombre que listarBackups() considera valido.
            // El UUID evita que dos ejecuciones en el mismo segundo compartan
            // el .part (el nombre final conserva la convencion historica).
            temporal = backupDir.resolve(filename + "." + java.util.UUID.randomUUID() + ".part");

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
                 FileOutputStream fos = new FileOutputStream(temporal.toFile());
                 GZIPOutputStream gzos = new GZIPOutputStream(fos)) {
                is.transferTo(gzos);
                gzos.finish();
            }
            exitCode = process.waitFor();

            if (exitCode != 0) {
                LOG.warn("pg_dump falló con código: " + exitCode
                        + " | source=BackupService.ejecutarBackup()");
                Files.deleteIfExists(temporal);
                return false;
            }

            Verificacion verificacion = verificarArchivo(temporal);
            if (!verificacion.valido()) {
                LOG.error("Backup rechazado: el archivo no es un gzip SQL legible o tiene 0 bytes"
                        + " | archivo=" + temporal.getFileName()
                        + " | source=BackupService.ejecutarBackup()");
                Files.deleteIfExists(temporal);
                return false;
            }

            publicar(temporal, finalFile);
            escribirChecksum(finalFile, verificacion.sha256());

            // Solo ahora, con el archivo verificado y publicado, se marca la
            // fecha de ultimo backup exitoso.
            settings.setBackupUltimoEjecutado(LocalDateTime.now());
            appSettingsService.update(settings);

            LOG.info("Backup verificado: " + filename
                    + " (" + getTamanioBackup(finalFile.toFile()) + ", sha256="
                    + verificacion.sha256() + ")"
                    + " | source=BackupService.ejecutarBackup()");

            limpiarBackupsViejos();
            return true;

        } catch (IOException e) {
            LOG.warn("Error de E/S en backup: " + e.getMessage()
                    + " | source=BackupService.ejecutarBackup()", e);
            borrarSilencioso(temporal);
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.warn("Backup interrumpido: " + e.getMessage()
                    + " | source=BackupService.ejecutarBackup()", e);
            borrarSilencioso(temporal);
            return false;
        } catch (RuntimeException e) {
            LOG.error("Backup fallido: " + e.getMessage()
                    + " | source=BackupService.ejecutarBackup()", e);
            borrarSilencioso(temporal);
            return false;
        }
    }

    /** Resultado de verificar un archivo de backup. */
    public record Verificacion(boolean valido, long bytes, @Nullable String sha256,
                               @Nullable String motivo) {
    }

    /** Estado del backup mas reciente para el monitoreo. */
    public record EstadoBackup(@Nullable String archivo,
                               @Nullable LocalDateTime fecha,
                               long edadDias,
                               boolean verificado,
                               boolean vencido,
                               @Nullable String sha256,
                               @Nullable String motivo) {
    }

    /**
     * Verifica que el archivo exista, tenga contenido, sea un gzip legible y
     * calcule su SHA-256. La lectura completa del gzip es la comprobacion mas
     * cercana a "restore" que se puede hacer sin una base desechable; un
     * archivo truncado falla aqui.
     */
    public @Nonnull Verificacion verificarArchivo(@Nonnull Path archivo) {
        if (!Files.isRegularFile(archivo)) {
            return new Verificacion(false, 0, null, "el archivo no existe");
        }
        try {
            long bytes = Files.size(archivo);
            if (bytes <= 0) {
                return new Verificacion(false, bytes, null, "archivo vacio");
            }
            String hash = sha256(archivo);
            try (InputStream entrada = Files.newInputStream(archivo);
                 GZIPInputStream gzip = new GZIPInputStream(entrada)) {
                gzip.transferTo(java.io.OutputStream.nullOutputStream());
            }
            return new Verificacion(true, bytes, hash, null);
        } catch (IOException e) {
            return new Verificacion(false, tamanoSeguro(archivo), null,
                    "gzip ilegible: " + e.getMessage());
        }
    }

    /**
     * Estado del backup mas reciente. Un archivo sin sidecar de checksum se
     * verifica igualmente leyendo el gzip; si el sidecar existe y no coincide,
     * el backup se considera no verificado.
     */
    @Nonnull
    public EstadoBackup estadoBackup() {
        try {
            ConfiguracionAplicacion settings = appSettingsService.findOrCreateCurrent();
            String backupRuta = settings == null ? null : settings.getBackupRuta();
            if (backupRuta == null || backupRuta.isBlank()) {
                return new EstadoBackup(null, null, Long.MAX_VALUE, false, true, null,
                        "no hay ruta de backups configurada");
            }
            Path dir = Paths.get(backupRuta);
            if (!Files.exists(dir)) {
                return new EstadoBackup(null, null, Long.MAX_VALUE, false, true, null,
                        "el directorio de backups no existe");
            }
            List<Path> archivos = archivosBackup(dir);
            if (archivos.isEmpty()) {
                return new EstadoBackup(null, null, Long.MAX_VALUE, false, true, null,
                        "no hay backups");
            }
            Path ultimo = archivos.get(0);
            Verificacion verificacion = verificarArchivo(ultimo);
            String esperado = leerChecksum(ultimo);
            boolean hashCoincide = esperado == null
                    || (verificacion.sha256() != null && esperado.equalsIgnoreCase(verificacion.sha256()));
            boolean verificado = verificacion.valido() && hashCoincide;
            LocalDateTime fecha = LocalDateTime.ofInstant(
                    Files.getLastModifiedTime(ultimo).toInstant(), java.time.ZoneId.systemDefault());
            long edad = Duration.between(fecha, LocalDateTime.now()).toDays();
            boolean vencido = !verificado || edad > Math.max(1, edadMaximaDias);
            if (vencido) {
                LOG.warn("Backup desactualizado o no verificado: " + ultimo.getFileName()
                        + " | edadDias=" + edad + " | verificado=" + verificado
                        + " | source=BackupService.estadoBackup()");
            }
            return new EstadoBackup(ultimo.getFileName().toString(), fecha, edad,
                    verificado, vencido, verificacion.sha256(),
                    verificacion.motivo());
        } catch (IOException e) {
            LOG.warn("No se pudo calcular el estado del backup: " + e.getMessage()
                    + " | source=BackupService.estadoBackup()", e);
            return new EstadoBackup(null, null, Long.MAX_VALUE, false, true, null,
                    "error de E/S: " + e.getMessage());
        }
    }

    private void publicar(@Nonnull Path temporal, @Nonnull Path finalFile) throws IOException {
        try {
            Files.move(temporal, finalFile,
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            LOG.debug("El sistema de archivos no soporta movimiento atomico; se usa reemplazo", e);
            Files.move(temporal, finalFile, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void escribirChecksum(@Nonnull Path archivo, @Nullable String hash) throws IOException {
        if (hash == null) {
            return;
        }
        Path sidecar = archivo.resolveSibling(archivo.getFileName() + ".sha256");
        Files.writeString(sidecar, hash + "  " + archivo.getFileName() + System.lineSeparator());
    }

    @Nullable
    private String leerChecksum(@Nonnull Path archivo) {
        Path sidecar = archivo.resolveSibling(archivo.getFileName() + ".sha256");
        if (!Files.isRegularFile(sidecar)) {
            return null;
        }
        try {
            String contenido = Files.readString(sidecar).trim();
            int espacio = contenido.indexOf(' ');
            return espacio > 0 ? contenido.substring(0, espacio) : contenido;
        } catch (IOException e) {
            LOG.warn("No se pudo leer el checksum de " + archivo.getFileName()
                    + ": " + e.getMessage() + " | source=BackupService.leerChecksum()");
            return null;
        }
    }

    private static String sha256(@Nonnull Path archivo) throws IOException {
        try (InputStream entrada = Files.newInputStream(archivo)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int leidos;
            while ((leidos = entrada.read(buffer)) != -1) {
                digest.update(buffer, 0, leidos);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM sin SHA-256", e);
        }
    }

    private void validarEspacio(@Nonnull Path backupDir) throws IOException {
        long disponible = Files.getFileStore(backupDir).getUsableSpace();
        if (disponible < espacioMinimoBytes) {
            throw new IOException("Espacio insuficiente para el backup: disponibles "
                    + disponible + " bytes, minimo " + espacioMinimoBytes);
        }
    }

    private static long tamanoSeguro(@Nonnull Path archivo) {
        try {
            return Files.size(archivo);
        } catch (IOException e) {
            return 0L;
        }
    }

    private static void borrarSilencioso(@Nullable Path archivo) {
        if (archivo == null) {
            return;
        }
        try {
            Files.deleteIfExists(archivo);
        } catch (IOException e) {
            LOG.warn("No se pudo borrar el archivo temporal/parcial " + archivo
                    + ": " + e.getMessage());
        }
    }

    private static List<Path> archivosBackup(@Nonnull Path dir) throws IOException {
        List<Path> archivos = new ArrayList<>();
        try (DirectoryStream<Path> stream =
                     Files.newDirectoryStream(dir, BACKUP_PREFIX + "*" + BACKUP_SUFFIX)) {
            for (Path entry : stream) {
                archivos.add(entry);
            }
        }
        archivos.sort(Comparator.comparingLong((Path p) -> {
            try {
                return p.toFile().lastModified();
            } catch (RuntimeException e) {
                return 0L;
            }
        }).reversed());
        return archivos;
    }

    public void limpiarBackupsViejos() {
        try {
            ConfiguracionAplicacion settings = appSettingsService.findOrCreateCurrent();
            if (settings == null) return;

            String backupRuta = settings.getBackupRuta();
            if (backupRuta == null || backupRuta.isBlank()) return;

            Integer retencionDias = settings.getBackupRetencionDias();
            if (retencionDias == null || retencionDias <= 0) {
                retencionDias = 7;
            }

            Path backupDir = Paths.get(backupRuta);
            if (!Files.exists(backupDir)) return;

            List<Path> backupFiles = archivosBackup(backupDir);
            for (int i = 0; i < backupFiles.size(); i++) {
                Path file = backupFiles.get(i);
                if (i == 0) {
                    // Nunca borrar el backup mas reciente, aunque la retencion
                    // ya lo haya cumplido: quedarse sin ningun respaldo es peor
                    // que conservar uno viejo.
                    continue;
                }
                BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class);
                long age = System.currentTimeMillis() - attrs.lastModifiedTime().toMillis();
                long ageDays = age / (24L * 60 * 60 * 1000);
                if (ageDays >= retencionDias) {
                    Files.delete(file);
                    Files.deleteIfExists(file.resolveSibling(file.getFileName() + ".sha256"));
                    LOG.info("Backup viejo eliminado: " + file.getFileName()
                            + " | source=BackupService.limpiarBackupsViejos()");
                }
            }

        } catch (IOException e) {
            LOG.warn("Error limpiando backups viejos: " + e.getMessage()
                    + " | source=BackupService.limpiarBackupsViejos()", e);
        }
    }

    public @Nonnull List<String> listarBackups() {
        List<String> result = new ArrayList<>();
        try {
            ConfiguracionAplicacion settings = appSettingsService.findOrCreateCurrent();
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
                        LOG.warn("Error listando backups: " + e.getMessage() + " | source=" + "BackupService.listarBackups()" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf(e.getMessage()));
        }
        return result;
    }

    public @Nonnull String getTamanioBackup(@Nonnull String filename) {
        try {
            ConfiguracionAplicacion settings = appSettingsService.findOrCreateCurrent();
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
            ConfiguracionAplicacion settings = appSettingsService.findOrCreateCurrent();
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

    public @Nullable ConfiguracionAplicacion getSettings() {
        return appSettingsService.findOrCreateCurrent();
    }

    public void saveSettings(@Nonnull ConfiguracionAplicacion settings) {
        appSettingsService.update(settings);
    }

    private String getTamanioBackup(File file) {
        long bytes = file.length();
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024));
    }

    public @Nonnull String rutaEfectiva() {
        try {
            ConfiguracionAplicacion settings = appSettingsService.findOrCreateCurrent();
            if (settings != null && settings.getBackupRuta() != null
                    && !settings.getBackupRuta().isBlank()) {
                return settings.getBackupRuta();
            }
        } catch (RuntimeException e) {
            LOG.warn("Error leyendo ruta de backups, usando defecto: " + e.getMessage());
        }
        return getDefaultBackupPath();
    }

    private String getDefaultBackupPath() {

        String mainDir;
        try {
            javax.swing.filechooser.FileSystemView fsv = javax.swing.filechooser.FileSystemView.getFileSystemView();
            mainDir = fsv.getDefaultDirectory().getAbsolutePath();
        } catch (Exception e) {
            mainDir = System.getProperty("user.home") + File.separator + "Documents";
        }

        ConfiguracionAplicacion settings = appSettingsService.findOrCreateCurrent();
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
                        LOG.info("No se pudo parsear JDBC URL: " + e.getMessage() + " | source=" + "BackupService.parseJdbcUrl()" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf((Object) null));
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
