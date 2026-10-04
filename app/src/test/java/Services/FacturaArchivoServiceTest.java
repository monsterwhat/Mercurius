package Services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * Archivo mensual de facturas a ZIP.
 *
 * <p>Test puro (temp dirs, sin Quarkus ni BD): archiva lo viejo, conserva lo
 * fresco y lo del mes en curso, sirve desde el ZIP con transparencia y la
 * recarrera es idempotente.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("FacturaArchivoService: archivo mensual a ZIP")
class FacturaArchivoServiceTest {

    @Mock private DirectoryService dirService;

    @TempDir private Path dir;

    private FacturaArchivoService servicio() {
        FacturaArchivoService s = new FacturaArchivoService();
        s.dirService = dirService;
        when(dirService.getFacturasDirPath()).thenReturn(dir.toString());
        return s;
    }

    private static Path escribir(Path dir, String nombre, String contenido, Instant mtime) throws Exception {
        Path p = dir.resolve(nombre);
        Files.write(p, contenido.getBytes(StandardCharsets.UTF_8));
        Files.setLastModifiedTime(p, FileTime.from(mtime));
        return p;
    }

    @Test
    @DisplayName("archiva lo viejo, conserva lo fresco y sirve desde el ZIP")
    void archivaYLee() throws Exception {
        Instant viejo = Instant.now().minus(120, ChronoUnit.DAYS);
        Instant fresco = Instant.now().minus(5, ChronoUnit.DAYS);
        YearMonth mesViejo = YearMonth.from(viejo.atZone(ZoneId.systemDefault()));

        byte[] pdfViejo = "PDF-viejo".getBytes(StandardCharsets.UTF_8);
        byte[] xmlViejo = "<xml/>".getBytes(StandardCharsets.UTF_8);
        escribir(dir, "tiqueteElectronico_1.pdf", "PDF-viejo", viejo);
        escribir(dir, "factura_2.xml", "<xml/>", viejo);
        escribir(dir, "tiqueteElectronico_9.pdf", "PDF-fresco", fresco);
        escribir(dir, "notas.txt", "no toca", viejo);

        FacturaArchivoService s = servicio();
        FacturaArchivoService.Stats stats = s.archivarAntiguas(90);

        assertThat(stats.archivados).isEqualTo(2);
        assertThat(dir.resolve("facturas-" + mesViejo + ".zip")).exists();
        assertThat(dir.resolve("tiqueteElectronico_1.pdf")).doesNotExist();
        assertThat(dir.resolve("factura_2.xml")).doesNotExist();
        assertThat(dir.resolve("tiqueteElectronico_9.pdf")).exists();
        assertThat(dir.resolve("notas.txt")).exists();

        // Lectura transparente: suelto, ZIP y ausente.
        assertThat(new String(s.leerFactura("tiqueteElectronico_9.pdf"), StandardCharsets.UTF_8))
                .isEqualTo("PDF-fresco");
        assertThat(s.leerFactura("tiqueteElectronico_1.pdf")).isEqualTo(pdfViejo);
        assertThat(s.leerFactura("factura_2.xml")).isEqualTo(xmlViejo);
        assertThat(s.leerFactura("no-existe.pdf")).isNull();
        // Traversal rechazado.
        assertThat(s.leerFactura("../otro.pdf")).isNull();
        assertThat(s.leerFactura("sub/dir.pdf")).isNull();

        // Recarrera idempotente: nada nuevo que archivar.
        FacturaArchivoService.Stats segunda = s.archivarAntiguas(90);
        assertThat(segunda.archivados).isZero();
        assertThat(s.leerFactura("tiqueteElectronico_1.pdf")).isEqualTo(pdfViejo);
    }

    @Test
    @DisplayName("no toca el mes en curso aunque supere la edad")
    void mesEnCursoSeConserva() throws Exception {
        YearMonth ahora = YearMonth.now();
        // Día 1 del mes en curso a las 00:00: viejo si dias=0, pero protegido por mes.
        Instant primero = ahora.atDay(1).atStartOfDay(ZoneId.systemDefault()).toInstant();
        escribir(dir, "tiqueteElectronico_5.pdf", "PDF-mes-actual", primero);

        FacturaArchivoService.Stats stats = servicio().archivarAntiguas(0);

        assertThat(stats.archivados).isZero();
        assertThat(dir.resolve("tiqueteElectronico_5.pdf")).exists();
    }
}
