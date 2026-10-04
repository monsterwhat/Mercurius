package Services;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import org.jboss.logging.Logger;

/**
 * Archivo mensual de facturas a ZIP.
 *
 * <p>El directorio {@code facturas/} del perfil acumula un PDF por venta
 * ({@code tiqueteElectronico_<id>.pdf}) más los XML que otros flujos dejen
 * ahí. Una vez al mes ({@code ProgramadorTareas}) lo anterior a
 * {@code diasAntiguedad} se mueve a {@code facturas-YYYY-MM.zip} dentro del
 * mismo directorio y se borran los originales — solo después de verificar
 * el ZIP reabriéndolo.</p>
 *
 * <p>La lectura es transparente: {@link #leerFactura} sirve el archivo suelto
 * si existe y si no lo busca en los ZIP del mes más reciente al más antiguo,
 * así que {@code GET /api/app/pos/facturas/{file}} y el reenvío por correo
 * siguen funcionando sin cambios de contrato.</p>
 *
 * <p>Lo que NO toca: el directorio {@code pdf/} (mezcla PDFs generados con
 * adjuntos de correo entrante que el parser puede reprocesar) ni {@code xml/}
 * (bandeja del parser). Solo {@code facturas/}.</p>
 */
@ApplicationScoped
public class FacturaArchivoService {

    private static final Logger LOG = Logger.getLogger(FacturaArchivoService.class);

    /** Prefijo de los ZIP mensuales dentro del directorio de facturas. */
    static final String ZIP_PREFIX = "facturas-";

    @Inject
    DirectoryService dirService;

    /** Resultado de una corrida de archivo, para logs y tests. */
    public static class Stats {
        public int archivados;
        public int omitidos;
        public long bytesOriginal;
        public long bytesZip;
    }

    /**
     * Lee una factura: archivo suelto primero, ZIP mensuales después.
     *
     * @param fileName nombre base (sin rutas); se rechaza traversal
     * @return bytes o {@code null} si no existe en ningún lado
     */
    public @Nullable byte[] leerFactura(@Nonnull String fileName) {
        if (fileName == null || fileName.isBlank()
                || fileName.contains("..") || fileName.contains("/") || fileName.contains("\\")) {
            return null;
        }
        Path dir = facturasDir();
        if (dir == null) {
            return null;
        }
        Path suelto = dir.resolve(fileName).normalize();
        if (!suelto.startsWith(dir)) {
            return null;
        }
        try {
            if (Files.isRegularFile(suelto)) {
                return Files.readAllBytes(suelto);
            }
        } catch (IOException e) {
            LOG.warn("no se pudo leer factura suelta " + fileName + " | source=FacturaArchivoService.leerFactura()");
        }
        for (Path zip : zipsRecientesPrimero(dir)) {
            try {
                byte[] data = leerDeZip(zip, fileName);
                if (data != null) {
                    return data;
                }
            } catch (IOException e) {
                LOG.warn("no se pudo leer " + fileName + " de " + zip.getFileName()
                        + " | source=FacturaArchivoService.leerFactura()");
            }
        }
        return null;
    }

    /**
     * Archiva a ZIP mensual lo anterior a {@code diasAntiguedad} días.
     * Solo {@code *.pdf} y {@code *.xml} (nunca {@code *.zip} ni el mes en curso).
     */
    public @Nonnull Stats archivarAntiguas(int diasAntiguedad) {
        Stats stats = new Stats();
        Path dir = facturasDir();
        if (dir == null || !Files.isDirectory(dir)) {
            return stats;
        }
        Instant corte = Instant.now().minusSeconds((long) diasAntiguedad * 24 * 3600);
        YearMonth mesActual = YearMonth.now();
        Map<YearMonth, List<Path>> porMes = new HashMap<>();
        try (DirectoryStream<Path> listado = Files.newDirectoryStream(dir)) {
            for (Path p : listado) {
                String nombre = p.getFileName().toString();
                if (!Files.isRegularFile(p)
                        || nombre.endsWith(".zip")
                        || !(nombre.endsWith(".pdf") || nombre.endsWith(".xml"))) {
                    stats.omitidos++;
                    continue;
                }
                FileTime mtime;
                try {
                    mtime = Files.getLastModifiedTime(p);
                } catch (IOException e) {
                    stats.omitidos++;
                    continue;
                }
                if (!mtime.toInstant().isBefore(corte)) {
                    stats.omitidos++;
                    continue;
                }
                YearMonth mes = YearMonth.from(mtime.toInstant().atZone(ZoneId.systemDefault()));
                if (mes.equals(mesActual)) {
                    stats.omitidos++;
                    continue;
                }
                porMes.computeIfAbsent(mes, k -> new ArrayList<>()).add(p);
            }
        } catch (IOException e) {
            LOG.warn("no se pudo listar facturas para archivar | source=FacturaArchivoService.archivarAntiguas()");
            return stats;
        }
        for (Map.Entry<YearMonth, List<Path>> e : porMes.entrySet()) {
            archivarMes(dir, e.getKey(), e.getValue(), stats);
        }
        LOG.info("archivo de facturas: " + stats.archivados + " archivados, "
                + stats.omitidos + " omitidos, " + stats.bytesOriginal + " -> "
                + stats.bytesZip + " bytes en ZIP"
                + " | source=FacturaArchivoService.archivarAntiguas()");
        return stats;
    }

    // ── internas ─────────────────────────────────────────────────────────

    private @Nullable Path facturasDir() {
        try {
            return Paths.get(dirService.getFacturasDirPath());
        } catch (RuntimeException e) {
            LOG.warn("sin directorio de facturas | source=FacturaArchivoService()");
            return null;
        }
    }

    private static List<Path> zipsRecientesPrimero(Path dir) {
        List<Path> zips = new ArrayList<>();
        try (DirectoryStream<Path> listado = Files.newDirectoryStream(dir, ZIP_PREFIX + "*.zip")) {
            for (Path p : listado) {
                zips.add(p);
            }
        } catch (IOException e) {
            return zips;
        }
        zips.sort(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed());
        return zips;
    }

    private static @Nullable byte[] leerDeZip(Path zip, String entryName) throws IOException {
        try (ZipFile zf = new ZipFile(zip.toFile())) {
            ZipEntry entry = zf.getEntry(entryName);
            if (entry == null || entry.isDirectory()) {
                return null;
            }
            try (InputStream in = zf.getInputStream(entry)) {
                return in.readAllBytes();
            }
        }
    }

    private static void archivarMes(Path dir, YearMonth mes, List<Path> archivos, Stats stats) {
        Path zip = dir.resolve(ZIP_PREFIX + mes + ".zip");
        Set<String> presentes = entradasExistentes(zip);
        List<Path> pendientes = new ArrayList<>();
        for (Path p : archivos) {
            if (!presentes.contains(p.getFileName().toString())) {
                pendientes.add(p);
            }
        }
        if (!pendientes.isEmpty()) {
            // APPEND no sirve para ZIP (el directorio central queda al final):
            // se reescribe el ZIP completo con lo existente + lo nuevo.
            reescribirZip(dir, zip, pendientes, stats);
            presentes = entradasExistentes(zip);
        }
        // Ventana de caída: el original sobrevivió aunque ya está en el ZIP
        // (corte entre escribir y borrar). Si los bytes coinciden, se borra.
        for (Path p : archivos) {
            String nombre = p.getFileName().toString();
            if (!pendientes.contains(p) && presentes.contains(nombre)) {
                try {
                    byte[] suelto = Files.readAllBytes(p);
                    byte[] enZip = leerDeZip(zip, nombre);
                    if (enZip != null && java.util.Arrays.equals(suelto, enZip)) {
                        stats.bytesOriginal += Files.size(p);
                        Files.deleteIfExists(p);
                        stats.archivados++;
                    }
                } catch (IOException e) {
                    LOG.warn("no se pudo conciliar duplicado " + nombre
                            + " | source=FacturaArchivoService.archivarMes()");
                }
            }
        }
        try {
            stats.bytesZip = Files.isRegularFile(zip) ? Files.size(zip) : stats.bytesZip;
        } catch (IOException e) {
            // conserva el último valor medido
        }
    }

    private static Set<String> entradasExistentes(Path zip) {
        Set<String> nombres = new HashSet<>();
        if (!Files.isRegularFile(zip)) {
            return nombres;
        }
        try (ZipFile zf = new ZipFile(zip.toFile())) {
            Enumeration<? extends ZipEntry> entries = zf.entries();
            while (entries.hasMoreElements()) {
                nombres.add(entries.nextElement().getName());
            }
        } catch (IOException e) {
            LOG.warn("ZIP existente ilegible, se reescribirá: " + zip.getFileName()
                    + " | source=FacturaArchivoService.entradasExistentes()");
        }
        return nombres;
    }

    /**
     * Reescribe el ZIP mensual = entradas existentes + pendientes, verifica
     * reabriéndolo y solo entonces borra los originales.
     */
    private static void reescribirZip(Path dir, Path zip, List<Path> pendientes, Stats stats) {
        Path tmp;
        try {
            tmp = Files.createTempFile(dir, ZIP_PREFIX + "tmp-", ".zip");
        } catch (IOException e) {
            LOG.warn("no se pudo crear temporal para " + zip.getFileName()
                    + " | source=FacturaArchivoService.reescribirZip()");
            return;
        }
        Map<String, byte[]> contenido = new HashMap<>();
        if (Files.isRegularFile(zip)) {
            try (ZipFile zf = new ZipFile(zip.toFile())) {
                Enumeration<? extends ZipEntry> entries = zf.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    if (!entry.isDirectory()) {
                        try (InputStream in = zf.getInputStream(entry)) {
                            contenido.put(entry.getName(), in.readAllBytes());
                        }
                    }
                }
            } catch (IOException e) {
                LOG.warn("ZIP existente ilegible, se parte de cero: " + zip.getFileName()
                        + " | source=FacturaArchivoService.reescribirZip()");
                contenido.clear();
            }
        }
        List<Path> agregados = new ArrayList<>();
        for (Path p : pendientes) {
            try {
                contenido.put(p.getFileName().toString(), Files.readAllBytes(p));
                agregados.add(p);
            } catch (IOException e) {
                LOG.warn("no se pudo leer para archivar " + p.getFileName()
                        + " | source=FacturaArchivoService.reescribirZip()");
            }
        }
        if (agregados.isEmpty()) {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
                // limpieza best-effort
            }
            return;
        }
        try (OutputStream fos = Files.newOutputStream(tmp);
             ZipOutputStream zos = new ZipOutputStream(fos)) {
            zos.setLevel(9);
            List<String> nombres = new ArrayList<>(contenido.keySet());
            nombres.sort(String::compareTo);
            for (String nombre : nombres) {
                zos.putNextEntry(new ZipEntry(nombre));
                zos.write(contenido.get(nombre));
                zos.closeEntry();
            }
        } catch (IOException e) {
            LOG.warn("no se pudo escribir " + tmp.getFileName()
                    + " | source=FacturaArchivoService.reescribirZip()");
            return;
        }
        // Verificación: el temporal debe abrirse y contener todo lo agregado.
        Set<String> verificadas = entradasExistentes(tmp);
        boolean ok = true;
        for (Path p : agregados) {
            if (!verificadas.contains(p.getFileName().toString())) {
                ok = false;
                break;
            }
        }
        if (!ok) {
            LOG.warn("verificación falló, no se borra nada: " + tmp.getFileName()
                    + " | source=FacturaArchivoService.reescribirZip()");
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
                // limpieza best-effort
            }
            return;
        }
        try {
            Files.move(tmp, zip, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            try {
                Files.move(tmp, zip, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ex) {
                LOG.warn("no se pudo publicar " + zip.getFileName()
                        + " | source=FacturaArchivoService.reescribirZip()");
                return;
            }
        }
        for (Path p : agregados) {
            try {
                stats.bytesOriginal += Files.size(p);
                Files.deleteIfExists(p);
                stats.archivados++;
            } catch (IOException e) {
                LOG.warn("archivado en ZIP pero no se pudo borrar original " + p.getFileName()
                        + " | source=FacturaArchivoService.reescribirZip()");
            }
        }
        try {
            stats.bytesZip = Files.size(zip);
        } catch (IOException e) {
            stats.bytesZip = 0;
        }
    }
}
