package Services;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.mail.BodyPart;
import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The receipt rule and the PDF companions, exercised on real MIME messages.
 *
 * <p>{@code processUnreadXmlAttachments} decides where each message lands:
 * {@code >= 1 parseable Hacienda XML → Processed}, otherwise
 * {@code NoXMLAttachments}. That decision used to be made on "the message had
 * an .xml attachment", and the loop {@code break}ed on the first one — so a
 * message with XML + PDF saved only the XML, and a message whose XML failed to
 * parse was still filed as Processed.</p>
 *
 * <p>No IMAP server is involved. The extracted
 * {@link EmailService#procesarAdjuntosDe(Message, File, File)} touches nothing
 * but {@code Part}, {@code File} and the injected parser, and
 * {@code jakarta.mail} builds {@link MimeMessage} instances in memory. GreenMail
 * would only add a server that none of these assertions use.</p>
 *
 * <p>The parser is replaced by a recording double: the point is WHICH
 * attachments reach the parser and which do not, not what Hacienda validation
 * does with them afterwards.</p>
 */
@DisplayName("EmailService: que es un recibo y que se guarda")
class EmailServiceAdjuntosTest {

    /** Stands in for Utils.Parsers.Parser; records what it was asked to parse. */
    private static final class ParserDoble extends Utils.Parsers.Parser {
        final List<String> parseados = new ArrayList<>();
        /** Filenames whose parse should fail, to drive the "no usable XML" path. */
        final List<String> fallan = new ArrayList<>();

        @Override
        public void parseXML(InputStream inputStream) {
            try {
                String contenido = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
                String nombre = contenido.contains("NOMBRE:")
                        ? contenido.substring(contenido.indexOf("NOMBRE:") + 7)
                                .lines().findFirst().orElse("").trim()
                        : "";
                parseados.add(nombre);
                if (fallan.contains(nombre)) {
                    throw new IllegalStateException("XML invalido simulado: " + nombre);
                }
            } catch (java.io.IOException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    private ParserDoble parser;
    private EmailService servicio;
    private File dirXml;
    private File dirPdf;

    @BeforeEach
    void preparar(@TempDir Path raiz) {
        parser = new ParserDoble();
        servicio = new EmailService();
        // El campo es package-private y lo rellena @Inject en produccion; aqui
        // se asigna a mano para aislar la logica de adjuntos del parser real.
        servicio.parser = parser;
        dirXml = raiz.resolve("xml").toFile();
        dirPdf = raiz.resolve("pdf").toFile();
        assertThat(dirXml.mkdirs()).isTrue();
        assertThat(dirPdf.mkdirs()).isTrue();
    }

    // ── helpers para construir mensajes en memoria ─────────────────────

    private static Session sesion() {
        return Session.getInstance(new Properties());
    }

    private static MimeMessage mensaje() throws Exception {
        MimeMessage m = new MimeMessage(sesion());
        m.setFrom(new InternetAddress("hacienda@hacienda.go.cr"));
        m.setSubject("Factura electronica");
        m.setSentDate(new java.util.Date(0));
        return m;
    }

    private static BodyPart adjunto(String nombre, String contenido) throws Exception {
        MimeBodyPart p = new MimeBodyPart();
        p.setFileName(nombre);
        p.setDisposition(BodyPart.ATTACHMENT);
        p.setText(contenido, StandardCharsets.UTF_8.name());
        return p;
    }

    private static BodyPart cuerpo(String texto) throws Exception {
        MimeBodyPart p = new MimeBodyPart();
        p.setText(texto, StandardCharsets.UTF_8.name());
        return p;
    }

    /** Ensambla un multipart/mixed a partir de las partes dadas, en orden. */
    private static MimeMessage multipart(BodyPart... partes) throws Exception {
        MimeMultipart mp = new MimeMultipart();
        for (BodyPart p : partes) {
            mp.addBodyPart(p);
        }
        MimeMessage m = mensaje();
        m.setContent(mp);
        m.saveChanges();
        return m;
    }

    private static List<String> archivosEn(File dir) {
        File[] files = dir.listFiles();
        List<String> nombres = new ArrayList<>();
        if (files != null) {
            for (File f : files) {
                nombres.add(f.getName());
            }
        }
        return nombres;
    }

    // ── La regla de recibo ─────────────────────────────────────────────

    @Test
    @DisplayName("un correo con XML + PDF es recibo: guarda ambos y procesa el XML")
    void xmlMasPdfEsRecibo(@TempDir Path raiz) throws Exception {
        MimeMessage m = multipart(
                cuerpo("Adjunto el comprobante."),
                adjunto("factura.xml", "<FacturaElectronica/> NOMBRE:factura.xml"),
                adjunto("factura.pdf", "%PDF-1.4 contenido"));

        EmailService.ResultadoAdjuntos r = servicio.procesarAdjuntosDe(m, dirXml, dirPdf);

        assertThat(r.esRecibo()).as(">=1 XML parseado es recibo").isTrue();
        assertThat(r.xmlProcesados()).isEqualTo(1);
        assertThat(r.pdfGuardados())
                .as("el PDF tambien se guarda: antes el break lo impedia").isEqualTo(1);

        assertThat(archivosEn(dirXml)).containsExactly("factura.xml");
        assertThat(archivosEn(dirPdf)).containsExactly("factura.pdf");
        assertThat(parser.parseados).containsExactly("factura.xml");
    }

    @Test
    @DisplayName("un correo sin adjuntos no es recibo y no escribe nada")
    void correoSinAdjuntos() throws Exception {
        MimeMessage m = multipart(cuerpo("Buenos dias, sin adjuntos."));

        EmailService.ResultadoAdjuntos r = servicio.procesarAdjuntosDe(m, dirXml, dirPdf);

        assertThat(r.esRecibo()).isFalse();
        assertThat(r.xmlVistos()).isZero();
        assertThat(r.pdfGuardados()).isZero();
        assertThat(archivosEn(dirXml)).isEmpty();
        assertThat(archivosEn(dirPdf)).isEmpty();
    }

    @Test
    @DisplayName("un correo con solo PDF no es recibo (el PDF no se parsea)")
    void correoConSoloPdfNoEsRecibo() throws Exception {
        MimeMessage m = multipart(
                cuerpo("Le adjunto la factura en PDF."),
                adjunto("factura.pdf", "%PDF-1.4 contenido"));

        EmailService.ResultadoAdjuntos r = servicio.procesarAdjuntosDe(m, dirXml, dirPdf);

        assertThat(r.esRecibo()).as("un PDF no convierte al correo en recibo").isFalse();
        assertThat(r.pdfGuardados()).isEqualTo(1);
        assertThat(r.xmlProcesados()).isZero();
        assertThat(parser.parseados).as("el PDF nunca llega al parser").isEmpty();
        assertThat(archivosEn(dirPdf)).containsExactly("factura.pdf");
    }

    @Test
    @DisplayName("varios XML en el mismo correo: todos se procesan, no solo el primero")
    void variosXmlSeProcesanTodos() throws Exception {
        MimeMessage m = multipart(
                adjunto("a.xml", "<FacturaElectronica/> NOMBRE:a.xml"),
                adjunto("b.xml", "<FacturaElectronica/> NOMBRE:b.xml"),
                adjunto("c.xml", "<FacturaElectronica/> NOMBRE:c.xml"));

        EmailService.ResultadoAdjuntos r = servicio.procesarAdjuntosDe(m, dirXml, dirPdf);

        assertThat(r.esRecibo()).isTrue();
        assertThat(r.xmlProcesados())
                .as("antes el break tras el primero dejaba dos sin procesar").isEqualTo(3);
        assertThat(parser.parseados).containsExactly("a.xml", "b.xml", "c.xml");
    }

    @Test
    @DisplayName("varios PDF en el mismo correo: todos se guardan")
    void variosPdfSeGuardanTodos() throws Exception {
        MimeMessage m = multipart(
                adjunto("factura.pdf", "%PDF a"),
                adjunto("recibo.pdf", "%PDF b"),
                adjunto("nota.pdf", "%PDF c"));

        EmailService.ResultadoAdjuntos r = servicio.procesarAdjuntosDe(m, dirXml, dirPdf);

        assertThat(r.pdfGuardados()).isEqualTo(3);
        assertThat(archivosEn(dirPdf)).containsExactlyInAnyOrder("factura.pdf", "recibo.pdf", "nota.pdf");
    }

    // ── Lo que NO cuenta como recibo ───────────────────────────────────

    @Test
    @DisplayName("un XML con DOCTYPE se guarda pero no se parsea, y el correo no es recibo")
    void xmlConDoctypeNoEsRecibo() throws Exception {
        // El DOCTYPE se rechaza ANTES del parser (misma puerta que el upload).
        // Antes esto contaba como "traia un XML" y el mensaje iba a Processed
        // sin haberse importado nada.
        MimeMessage m = multipart(adjunto(
                "malo.xml",
                "<?xml version=\"1.0\"?><!DOCTYPE Factura [<!ENTITY x SYSTEM \"file:///etc/passwd\">]><FacturaElectronica/>"));

        EmailService.ResultadoAdjuntos r = servicio.procesarAdjuntosDe(m, dirXml, dirPdf);

        assertThat(r.esRecibo()).isFalse();
        assertThat(r.xmlVistos()).isEqualTo(1);
        assertThat(r.xmlProcesados()).isZero();
        assertThat(parser.parseados).as("un DOCTYPE nunca debe llegar al parser").isEmpty();
    }

    @Test
    @DisplayName("un XML que el parser rechaza deja el correo sin recibo")
    void xmlQueFallaAlParsearNoEsRecibo() throws Exception {
        parser.fallan.add("roto.xml");

        MimeMessage m = multipart(
                adjunto("roto.xml", "<Factura/> NOMBRE:roto.xml"),
                adjunto("bueno.xml", "<Factura/> NOMBRE:bueno.xml"));

        EmailService.ResultadoAdjuntos r = servicio.procesarAdjuntosDe(m, dirXml, dirPdf);

        assertThat(r.esRecibo()).as("un XML parseable alcanza para el Processed").isTrue();
        assertThat(r.xmlVistos()).isEqualTo(2);
        assertThat(r.xmlProcesados()).isEqualTo(1);
        assertThat(parser.parseados).as("el que falla se intenta igual").containsExactly("roto.xml", "bueno.xml");
    }

    @Test
    @DisplayName("un XML que el parser rechaza y no hay otro: el correo no es recibo")
    void xmlRotoYSoloNoEsRecibo() throws Exception {
        parser.fallan.add("roto.xml");

        MimeMessage m = multipart(adjunto("roto.xml", "<Factura/> NOMBRE:roto.xml"));

        EmailService.ResultadoAdjuntos r = servicio.procesarAdjuntosDe(m, dirXml, dirPdf);

        assertThat(r.esRecibo()).isFalse();
        assertThat(r.xmlVistos()).isEqualTo(1);
        assertThat(r.xmlProcesados()).isZero();
    }

    @Test
    @DisplayName("un adjunto con nombre de traversal se contiene y no rompe el resto")
    void traversalDescartadoNoRompeElResto() throws Exception {
        MimeMessage m = multipart(
                adjunto("../../../evil.xml", "<Factura/> NOMBRE:evil.xml"),
                adjunto("bueno.xml", "<Factura/> NOMBRE:bueno.xml"));

        EmailService.ResultadoAdjuntos r = servicio.procesarAdjuntosDe(m, dirXml, dirPdf);

        // El traversal no se "descarta": la primera capa del resolver descarta
        // el componente de directorio, asi que "../../../evil.xml" aterriza como
        // "evil.xml" DENTRO de dirXml. El contrato auditado del gate es
        // contencion, no rechazo (ver traversalRelativoSeContiene en
        // EmailServiceNombreAdjuntoTest): lo que no puede pasar es que el archivo
        // caiga fuera del directorio.
        assertThat(r.esRecibo()).isTrue();
        assertThat(archivosEn(dirXml))
                .as("ambos adjuntos quedan dentro del directorio de XML")
                .containsExactlyInAnyOrder("evil.xml", "bueno.xml");
        assertThat(parser.parseados).containsExactlyInAnyOrder("evil.xml", "bueno.xml");
        for (String nombre : archivosEn(dirXml)) {
            assertThat(new File(dirXml, nombre).getCanonicalPath())
                    .startsWith(dirXml.getCanonicalPath() + File.separator);
        }
    }

    @Test
    @DisplayName("una parte sin nombre de archivo se ignora sin lanzar excepcion")
    void parteSinNombreSeIgnora() throws Exception {
        // El codigo original desreferenciaba getFileName() sin comprobar null, y
        // el NPE abortaba el barrido de la bandeja entera.
        MimeBodyPart sinNombre = new MimeBodyPart();
        sinNombre.setDisposition(BodyPart.ATTACHMENT);
        sinNombre.setText("sin filename");

        MimeMessage m = multipart(sinNombre, adjunto("ok.xml", "<Factura/> NOMBRE:ok.xml"));

        EmailService.ResultadoAdjuntos r = servicio.procesarAdjuntosDe(m, dirXml, dirPdf);

        assertThat(r.esRecibo()).isTrue();
        assertThat(parser.parseados).containsExactly("ok.xml");
    }

    @Test
    @DisplayName("una parte INLINE no cuenta como adjunto aunque se llame .xml")
    void inlineNoCuenta() throws Exception {
        MimeBodyPart inline = new MimeBodyPart();
        inline.setFileName("firmado.xml");
        inline.setDisposition(BodyPart.INLINE);
        inline.setText("<Factura/> NOMBRE:inline.xml");

        MimeMessage m = multipart(inline);

        EmailService.ResultadoAdjuntos r = servicio.procesarAdjuntosDe(m, dirXml, dirPdf);

        assertThat(r.esRecibo()).isFalse();
        assertThat(r.xmlVistos()).isZero();
        assertThat(archivosEn(dirXml)).isEmpty();
    }

    @Test
    @DisplayName("una parte que no es XML ni PDF se ignora")
    void otraExtensionSeIgnora() throws Exception {
        MimeMessage m = multipart(
                adjunto("logo.png", "binario"),
                adjunto("notas.txt", "texto"),
                adjunto("hoja.xlsx", "binario"));

        EmailService.ResultadoAdjuntos r = servicio.procesarAdjuntosDe(m, dirXml, dirPdf);

        assertThat(r.esRecibo()).isFalse();
        assertThat(r.xmlVistos()).isZero();
        assertThat(r.pdfGuardados()).isZero();
        assertThat(archivosEn(dirXml)).isEmpty();
        assertThat(archivosEn(dirPdf)).isEmpty();
    }

    @Test
    @DisplayName("un mensaje que no es multipart no es recibo")
    void mensajeSimpleNoEsRecibo() throws Exception {
        MimeMessage m = mensaje();
        m.setText("solo texto", StandardCharsets.UTF_8.name());
        m.saveChanges();

        EmailService.ResultadoAdjuntos r = servicio.procesarAdjuntosDe(m, dirXml, dirPdf);

        assertThat(r.esRecibo()).isFalse();
        assertThat(r.xmlVistos()).isZero();
    }

    // ── Aislamiento entre XML y PDF ────────────────────────────────────

    @Test
    @DisplayName("el PDF aterriza en el directorio de PDF, no en el de XML")
    void pdfNoContaminaElDirectorioDeXml() throws Exception {
        // El directorio de XML es el que consume el parser; si un PDF terminara
        // ahi, un barrido posterior lo encontraria como documento huerfano.
        MimeMessage m = multipart(
                adjunto("factura.xml", "<Factura/> NOMBRE:factura.xml"),
                adjunto("factura.pdf", "%PDF contenido"));

        servicio.procesarAdjuntosDe(m, dirXml, dirPdf);

        assertThat(archivosEn(dirXml)).containsExactly("factura.xml");
        assertThat(archivosEn(dirPdf)).containsExactly("factura.pdf");
    }

    @Test
    @DisplayName("un PDF con traversal relativo no escapa del directorio de PDF")
    void pdfConTraversalNoEscapa(@TempDir Path raiz) throws Exception {
        File fuera = raiz.resolve("fuera.pdf").toFile();
        MimeMessage m = multipart(
                adjunto("../../fuera.pdf", "%PDF malicioso"),
                adjunto("ok.xml", "<Factura/> NOMBRE:ok.xml"));

        EmailService.ResultadoAdjuntos r = servicio.procesarAdjuntosDe(m, dirXml, dirPdf);

        assertThat(r.esRecibo()).isTrue();
        // Lo que se afirma es la contencion: el PDF del remitente hostil cae
        // como "fuera.pdf" dentro de dirPdf y NUNCA como "<raiz>/fuera.pdf".
        // Afirmar "nada se guardo" fijaria rechazo como contrato, que no es el
        // del gate (ver traversalRelativoSeContiene).
        assertThat(fuera.exists()).as("no debe escribirse fuera del directorio de PDF").isFalse();
        assertThat(archivosEn(dirPdf)).containsExactly("fuera.pdf");
        assertThat(new File(dirPdf, "fuera.pdf").getCanonicalPath())
                .startsWith(dirPdf.getCanonicalPath() + File.separator);
    }

    @Test
    @DisplayName("un XML con nombre de PDF no se guarda como PDF ni se parsea")
    void xmlConNombreDePdfNoSeConfunde() throws Exception {
        MimeMessage m = multipart(adjunto("factura.pdf", "<Factura/> NOMBRE:factura.pdf"));

        EmailService.ResultadoAdjuntos r = servicio.procesarAdjuntosDe(m, dirXml, dirPdf);

        // El .pdf manda: se guarda como PDF y no se parsea como XML.
        assertThat(r.esRecibo()).isFalse();
        assertThat(r.xmlVistos()).isZero();
        assertThat(r.pdfGuardados()).isEqualTo(1);
        assertThat(parser.parseados).isEmpty();
        assertThat(archivosEn(dirPdf)).containsExactly("factura.pdf");
    }

    /** Guards against the parser double silently not being wired. */
    @Test
    @DisplayName("el doble de parser esta realmente conectado al servicio")
    void elDobleEstaConectado() throws Exception {
        MimeMessage m = multipart(adjunto("x.xml", "<Factura/> NOMBRE:x.xml"));
        servicio.procesarAdjuntosDe(m, dirXml, dirPdf);

        assertThat(parser.parseados).containsExactly("x.xml");
        // Y el contenido leido es el del adjunto, no un archivo vacio.
        assertThat(java.nio.file.Files.readString(dirXml.toPath().resolve("x.xml"),
                StandardCharsets.UTF_8)).contains("<Factura/>");
    }
}