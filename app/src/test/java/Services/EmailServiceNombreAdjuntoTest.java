package Services;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * An e-mailed attachment filename must never decide where the file lands.
 *
 * <p>Behavioral pin for the fix in
 * {@code EmailService.processUnreadXmlAttachments}. The filename comes from
 * whoever sent the message and was passed straight to
 * {@code new File(directory, mimeBodyPart.getFileName())}, so a remote sender
 * could escape the target directory with {@code ../} segments and choose the
 * destination extension — arbitrary file write with the service account's
 * privileges. The same block also dereferenced {@code getFileName()} without a
 * null check, throwing an NPE that aborted the whole mailbox sweep when a MIME
 * part carried no filename.</p>
 *
 * <p>{@code resolverNombreSeguroAdjunto} is package-private and takes the
 * directory plus the raw name, so the traversal decision is testable without an
 * IMAP server.</p>
 */
@DisplayName("EmailService: el nombre del adjunto no decide la ruta")
class EmailServiceNombreAdjuntoTest {

    private static String baseCanonical(Path destino) {
        try {
            return destino.toFile().getCanonicalPath();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * The security property is CONTAINMENT, not a particular rejection policy.
     *
     * <p>Asserting {@code isNull()} for every traversal string would over-fit
     * the implementation: the fix reduces the sender-controlled name to its last
     * path segment, so {@code ../../../evil.xml} becomes {@code evil.xml}
     * written inside the target directory. That is safer than refusing the
     * attachment outright (a real supplier may legitimately send
     * {@code 2026/factura.xml}) and equally unexploitable. What must never happen
     * is a resolved path outside the destination, so that is what is asserted.</p>
     */
    @Test
    @DisplayName("un nombre con traversal relativo nunca sale del directorio destino")
    void traversalRelativoSeContiene(@TempDir Path destino) throws Exception {
        File base = destino.toFile();

        String[] nombres = {
            "../../../evil.xml",
            "../../evil.xml",
            "..\\..\\evil.xml",
            "factura/../../../evil.xml",
            "....//....//evil.xml",
            "..evil.xml",
            "a..b.xml"
        };
        for (String nombre : nombres) {
            File archivo = EmailService.resolverNombreSeguroAdjunto(base, nombre);
            if (archivo == null) {
                continue; // rechazado: tambien es una respuesta segura
            }
            assertThat(archivo.getCanonicalPath())
                    .as("el adjunto %s no debe resolver fuera del destino", nombre)
                    .startsWith(baseCanonical(destino) + File.separator);
        }
    }

    @Test
    @DisplayName("un nombre con prefijo de unidad de disco no escapa del destino")
    void prefijoDeDiscoSeContiene(@TempDir Path destino) throws Exception {
        File base = destino.toFile();

        for (String nombre : new String[]{"C:\\Windows\\Temp\\evil.xml", "C:evil.xml", "D:/x/evil.xml"}) {
            File archivo = EmailService.resolverNombreSeguroAdjunto(base, nombre);
            if (archivo == null) {
                continue;
            }
            assertThat(archivo.getCanonicalPath())
                    .as("una letra de unidad no puede sacar el archivo del destino: %s", nombre)
                    .startsWith(baseCanonical(destino) + File.separator);
        }
    }

    @Test
    @DisplayName("una ruta absoluta se reduce al nombre final dentro del destino")
    void rutaAbsolutaSeReduceAlDirectorio(@TempDir Path destino) {
        File base = destino.toFile();

        File archivo = EmailService.resolverNombreSeguroAdjunto(base, "/etc/cron.d/evil.xml");

        assertThat(archivo).as("solo debe sobrevivir el nombre final").isNotNull();
        assertThat(archivo.getName()).isEqualTo("evil.xml");
        assertThat(baseCanonical(destino))
                .isEqualTo(baseCanonical(archivo.getParentFile().toPath()));
    }

    @Test
    @DisplayName("se aceptan los nombres normales de una factura")
    void nombreNormalSeAcepta(@TempDir Path destino) {
        File base = destino.toFile();

        File archivo = EmailService.resolverNombreSeguroAdjunto(base, "factura-001.xml");

        assertThat(archivo).isNotNull();
        assertThat(archivo.getName()).isEqualTo("factura-001.xml");
        assertThat(baseCanonical(destino))
                .isEqualTo(baseCanonical(archivo.getParentFile().toPath()));
    }

    @Test
    @DisplayName("ningun adjunto resuelto queda fuera del directorio destino")
    void loResueltoPermaneceDentro(@TempDir Path destino) throws Exception {        File base = destino.toFile();

        String[] nombres = {
            "factura.xml", "a-1_2.xml", "sub/carpeta/factura.xml",
            "../factura.xml", "/tmp/factura.xml", "factura.exe"
        };
        for (String nombre : nombres) {
            File archivo = EmailService.resolverNombreSeguroAdjunto(base, nombre);
            if (archivo == null) {
                continue;
            }
            assertThat(archivo.getCanonicalPath())
                    .as("el adjunto %s no debe resolver fuera del destino", nombre)
                    .startsWith(baseCanonical(destino) + File.separator);
        }
    }

    @Test
    @DisplayName("un nombre vacio, sin extension .xml o con salto de linea se rechaza")
    void nombresInvalidosSeRechazan(@TempDir Path destino) {
        File base = destino.toFile();

        assertThat(EmailService.resolverNombreSeguroAdjunto(base, "")).isNull();
        assertThat(EmailService.resolverNombreSeguroAdjunto(base, "   ")).isNull();
        assertThat(EmailService.resolverNombreSeguroAdjunto(base, "factura.pdf")).isNull();
        assertThat(EmailService.resolverNombreSeguroAdjunto(base, "factura")).isNull();
        assertThat(EmailService.resolverNombreSeguroAdjunto(base, "factura.xml.exe")).isNull();
        assertThat(EmailService.resolverNombreSeguroAdjunto(base, "a.xml\nb")).isNull();
    }

    @Test
    @DisplayName("un nombre excesivamente largo se rechaza")
    void nombreExcesivoSeRechaza(@TempDir Path destino) {
        StringBuilder largo = new StringBuilder();
        for (int i = 0; i < 300; i++) {
            largo.append('a');
        }
        assertThat(EmailService.resolverNombreSeguroAdjunto(base(destino), largo + ".xml")).isNull();
    }

    private static File base(Path destino) {
        return destino.toFile();
    }
}
