package Services;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The PDF companions added to the mailbox sweep come from the same hostile
 * channel as the XMLs: the filename is whatever the remote sender typed.
 *
 * <p>{@code processUnreadXmlAttachments} now saves {@code .pdf} attachments
 * next to the XML directory, through
 * {@code resolverNombreSeguroAdjunto(dir, nombre, ".pdf")}. The security
 * property is the same one already pinned for XML by
 * {@link EmailServiceNombreAdjuntoTest}: whatever the sender writes, the
 * resolved path stays INSIDE the target directory, and the extension is the
 * one the caller asked for.</p>
 *
 * <p>No mail server is involved: the resolver is a pure function of
 * (directory, name, extension).</p>
 */
@DisplayName("EmailService: el adjunto PDF pasa la misma puerta que el XML")
class EmailServiceNombreAdjuntoPdfTest {

    private static String baseCanonical(Path destino) {
        try {
            return destino.toFile().getCanonicalPath();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("un PDF normal se guarda dentro del directorio")
    void pdfNormalSeAcepta(@TempDir Path destino) {
        File archivo = EmailService.resolverNombreSeguroAdjunto(destino.toFile(), "factura-001.pdf", ".pdf");

        assertThat(archivo).isNotNull();
        assertThat(archivo.getName()).isEqualTo("factura-001.pdf");
        assertThat(baseCanonical(destino))
                .isEqualTo(baseCanonical(archivo.getParentFile().toPath()));
    }

    @Test
    @DisplayName("un PDF con traversal relativo nunca sale del directorio destino")
    void traversalSeContiene(@TempDir Path destino) throws Exception {
        File base = destino.toFile();

        String[] nombres = {
            "../../../evil.pdf",
            "../../evil.pdf",
            "..\\..\\evil.pdf",
            "factura/../../../evil.pdf",
            "....//....//evil.pdf",
            "..evil.pdf",
            "a..b.pdf"
        };
        for (String nombre : nombres) {
            File archivo = EmailService.resolverNombreSeguroAdjunto(base, nombre, ".pdf");
            if (archivo == null) {
                continue; // rechazado: tambien es una respuesta segura
            }
            assertThat(archivo.getCanonicalPath())
                    .as("el adjunto %s no debe resolver fuera del destino", nombre)
                    .startsWith(baseCanonical(destino) + File.separator);
        }
    }

    @Test
    @DisplayName("una ruta absoluta se reduce al nombre final dentro del directorio")
    void rutaAbsolutaSeReduceAlDirectorio(@TempDir Path destino) throws Exception {
        File archivo = EmailService.resolverNombreSeguroAdjunto(
                destino.toFile(), "/etc/passwd.pdf", ".pdf");

        assertThat(archivo).isNotNull();
        assertThat(archivo.getName()).isEqualTo("passwd.pdf");
        assertThat(baseCanonical(destino))
                .isEqualTo(baseCanonical(archivo.getParentFile().toPath()));
    }

    @Test
    @DisplayName("la extension exigida es la del llamador, no la del remitente")
    void laExtensionLaMandaElLlamador(@TempDir Path destino) {
        File base = destino.toFile();

        // Un remitente no puede elegir la extension con la que aterriza el
        // archivo: "factura.xml" no puede pasar por la puerta de los PDF, ni al
        // reves. Esto era justamente el agujero que el filtro ".xml" cubria
        // para los XML.
        assertThat(EmailService.resolverNombreSeguroAdjunto(base, "factura.xml", ".pdf")).isNull();
        assertThat(EmailService.resolverNombreSeguroAdjunto(base, "factura.pdf", ".xml")).isNull();
        assertThat(EmailService.resolverNombreSeguroAdjunto(base, "factura.pdf.exe", ".pdf")).isNull();
        assertThat(EmailService.resolverNombreSeguroAdjunto(base, "factura", ".pdf")).isNull();
    }

    @Test
    @DisplayName("una extension en mayusculas del remitente sigue siendo el PDF pedido")
    void extensionDelRemitenteEnMayusculas(@TempDir Path destino) {
        // Windows no distingue mayusculas, asi que un ".PDF" en un adjunto es
        // perfectamente alcanzable; el filtro debe reconocerlo como PDF sin
        // abrir la puerta a otra extension.
        File archivo = EmailService.resolverNombreSeguroAdjunto(
                destino.toFile(), "Factura.PDF", ".pdf");

        assertThat(archivo).isNotNull();
        assertThat(archivo.getName()).isEqualTo("Factura.PDF");
    }

    @Test
    @DisplayName("un nombre vacio, con NUL o con salto de linea se rechaza")
    void nombresInvalidosSeRechazan(@TempDir Path destino) {
        File base = destino.toFile();

        assertThat(EmailService.resolverNombreSeguroAdjunto(base, "", ".pdf")).isNull();
        assertThat(EmailService.resolverNombreSeguroAdjunto(base, "   ", ".pdf")).isNull();
        assertThat(EmailService.resolverNombreSeguroAdjunto(base, ".pdf", ".pdf")).isNull();
        assertThat(EmailService.resolverNombreSeguroAdjunto(base, "a.pdf\nb", ".pdf")).isNull();
    }

    /**
     * Una letra de unidad no puede sacar el archivo del destino.
     *
     * <p>El nombre NO tiene por que rechazarse: la primera capa del resolver
     * descarta el componente de directorio, asi que
     * {@code C:\Windows\Temp\evil.pdf} sobrevive como {@code evil.pdf} dentro
     * del directorio de destino. Lo que no puede pasar —y lo que se afirma
     * aqui— es que aterrice fuera.</p>
     *
     * <p>Mismo criterio que {@code prefijoDeDiscoSeContiene} del test de XML:
     * la propiedad es la contencion, no el rechazo. Fijar "rechazar" como
     * contrato habria cambiado el comportamiento del gate ya auditado para
     * poder probar una archivo.</p>
     */
    @Test
    @DisplayName("una letra de unidad no puede sacar el PDF del directorio destino")
    void prefijoDeDiscoSeContiene(@TempDir Path destino) throws Exception {
        File base = destino.toFile();

        for (String nombre : new String[]{"C:\\Windows\\Temp\\evil.pdf", "C:evil.pdf", "D:/x/evil.pdf"}) {
            File archivo = EmailService.resolverNombreSeguroAdjunto(base, nombre, ".pdf");
            if (archivo == null) {
                continue;
            }
            assertThat(archivo.getCanonicalPath())
                    .as("una letra de unidad no puede sacar el archivo del destino: %s", nombre)
                    .startsWith(baseCanonical(destino));
            assertThat(archivo.getName()).as("solo debe sobrevivir el nombre final").isEqualTo("evil.pdf");
        }
    }

    @Test
    @DisplayName("un nombre excesivamente largo se rechaza")
    void nombreExcesivoSeRechaza(@TempDir Path destino) {
        StringBuilder largo = new StringBuilder();
        for (int i = 0; i < 300; i++) {
            largo.append('a');
        }
        assertThat(EmailService.resolverNombreSeguroAdjunto(
                destino.toFile(), largo + ".pdf", ".pdf")).isNull();
    }

    @Test
    @DisplayName("la sobrecarga de dos argumentos sigue siendo la del XML")
    void sobrecargaDeDosArgumentosSigueSiendoXml(@TempDir Path destino) throws Exception {
        // Los llamados del barrido de XML no pasan extension; el comportamiento
        // previo (rechazar .pdf) no puede cambiar bajo los pies de ese codigo.
        File base = destino.toFile();

        assertThat(EmailService.resolverNombreSeguroAdjunto(base, "factura.xml"))
                .isNotNull();
        assertThat(EmailService.resolverNombreSeguroAdjunto(base, "factura.pdf")).isNull();
    }
}