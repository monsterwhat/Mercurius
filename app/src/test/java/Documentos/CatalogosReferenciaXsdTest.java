package Documentos;

import Models.Enums.Tipo_CodigosReferencia;
import Models.Enums.Tipo_Documento_Referencia;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the bundled v4.4 schemas to the revision Hacienda published on
 * 2026-04-22 ("Bitácora de Ajustes", mandatory from 2026-11-01).
 *
 * <p>Two traps this guards against, both of which bit us:</p>
 *
 * <ol>
 *   <li><b>Two hosts serve identically-named files.</b>
 *   {@code atv.hacienda.go.cr/.../esquemas/2024/v4.4/} still answers HTTP 200
 *   but is frozen at 2025-09-09 and predates the bitácora. Hacienda migrated
 *   from ATV to TRIBU-CR on 2025-10-06 and never refreshed that path, so a
 *   plain fetch succeeds against a superseded schema and fails silently. The
 *   current set lives at {@code www.hacienda.go.cr/docs/}. That host also
 *   answers a bodyless 400 to browser User-Agents and 200 to {@code curl/8.4.0}
 *   — see the fetch notes in the commit message.</li>
 *   <li><b>The catalog is not uniform.</b> Nota 9 code 17 ("Pago a comprobante
 *   electrónico") exists ONLY in {@code ReciboElectronicoPago_V4.4.xsd}. It is
 *   legal on a REP and rejected everywhere else, so a single shared 13-17 list
 *   is wrong.</li>
 * </ol>
 *
 * <p>No database, no HTTP, no Quarkus: it reads the XSDs off the classpath.
 * It asserts on catalog and pattern content, not on schema compilation — the
 * production validator compiles these for real on every emission, and its
 * {@code LSResourceResolver} already handles the {@code ../../xmldsig-core-schema.xsd}
 * relative import, which a standalone {@code SchemaFactory} cannot.</p>
 */
class CatalogosReferenciaXsdTest {

    private static final String DIR = "/xsd/v4.4/";

    /** The seven document schemas; the two Mensaje* schemas carry neither catalog. */
    private static final List<String> DOCUMENTOS = List.of(
            "FacturaElectronica_V4.4.xsd",
            "NotaCreditoElectronica_V4.4.xsd",
            "NotaDebitoElectronica_V4.4.xsd",
            "TiqueteElectronico_V4.4.xsd",
            "FacturaElectronicaCompra_V4.4.xsd",
            "FacturaElectronicaExportacion_V4.4.xsd",
            "ReciboElectronicoPago_V4.4.xsd");

    private static final String REP = "ReciboElectronicoPago_V4.4.xsd";

    // ── the codes the 2026-04-22 revision added ───────────────────────────

    @Test
    @DisplayName("every document schema carries Nota 9 codes 13-16 and Nota 10 codes 19-20")
    void losCatalogosTienenLosCodigosDeLaRevision2026() {
        for (String esquema : DOCUMENTOS) {
            List<String> n9 = enums(esquema, "CodigoReferenciaType");
            for (String codigo : List.of("13", "14", "15", "16")) {
                assertTrue(n9.contains(codigo), esquema + " deberia incluir el codigo " + codigo
                        + " de la nota 9. Tiene: " + n9);
            }
            List<String> n10 = enums(esquema, "TipoDocReferenciaType");
            for (String codigo : List.of("19", "20")) {
                assertTrue(n10.contains(codigo), esquema + " deberia incluir el codigo " + codigo
                        + " de la nota 10. Tiene: " + n10);
            }
        }
    }

    @Test
    @DisplayName("Nota 9 code 17 exists ONLY in ReciboElectronicoPago")
    void elCodigo17EsExclusivoDelReciboElectronicoDePago() {
        assertTrue(enums(REP, "CodigoReferenciaType").contains("17"),
                REP + " deberia incluir el 17: es de uso exclusivo del Recibo Electronico de Pago");
        for (String esquema : DOCUMENTOS) {
            if (esquema.equals(REP)) {
                continue;
            }
            assertFalse(enums(esquema, "CodigoReferenciaType").contains("17"), esquema
                    + " NO debe aceptar el 17; el esquema oficial lo rechaza. Si esto falla, "
                    + "el XSD local se desincronizo del oficial.");
        }
    }

    // ── what the app can emit must be what the schema accepts ─────────────

    @Test
    @DisplayName("every Tipo_Documento_Referencia code is accepted by all seven schemas")
    void todosLosCodigosDeTipoDocumentoReferenciaEstanAceptados() {
        List<String> faltantes = new ArrayList<>();
        for (Tipo_Documento_Referencia v : Tipo_Documento_Referencia.values()) {
            for (String esquema : DOCUMENTOS) {
                if (!enums(esquema, "TipoDocReferenciaType").contains(v.getCodigo())) {
                    faltantes.add(v.getCodigo() + " ausente de " + esquema);
                }
            }
        }
        assertTrue(faltantes.isEmpty(),
                "el enum ofrece codigos que el esquema oficial rechaza: " + faltantes);
    }

    @Test
    @DisplayName("Tipo_CodigosReferencia codes are accepted wherever they are legal")
    void todosLosCodigosDeReferenciaEstanAceptadosDondeCorresponde() {
        List<String> problemas = new ArrayList<>();
        for (Tipo_CodigosReferencia v : Tipo_CodigosReferencia.values()) {
            for (String esquema : DOCUMENTOS) {
                // 17 is deliberately absent outside REP; asserted separately.
                if ("17".equals(v.getCodigo()) && !esquema.equals(REP)) {
                    continue;
                }
                if (!enums(esquema, "CodigoReferenciaType").contains(v.getCodigo())) {
                    problemas.add(v.getCodigo() + " ausente de " + esquema);
                }
            }
        }
        assertTrue(problemas.isEmpty(),
                "el enum ofrece codigos que el esquema oficial rechaza: " + problemas);
    }

    // ── the Clave pattern widened to alphanumeric ──────────────────────────

    @Test
    @DisplayName("Clave accepts alphanumerics, as the bitacora requires")
    void laClaveAceptaAlfanumericos() {
        for (String esquema : DOCUMENTOS) {
            assertEquals("[a-zA-Z0-9]{50,50}", patronDeClave(esquema), esquema
                    + ": la revision del 2026-04-22 permitio identificadores alfanumericos de "
                    + "persona juridica. Si vuelve a \\d{50,50}, el XSD local quedo atrasado "
                    + "respecto al oficial y rechazaria comprobantes validos.");
        }
    }

    // ── the schemas must be well-formed and complete ──────────────────────

    @Test
    @DisplayName("all nine bundled v4.4 schemas are present and well-formed")
    void losNueveEsquemasEstanYSonXMLValido() throws Exception {
        List<String> todos = new ArrayList<>(DOCUMENTOS);
        todos.add("MensajeHacienda_V4.4.xsd");
        todos.add("MensajeReceptor_V4.4.xsd");
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(true);
        for (String esquema : todos) {
            try (InputStream in = open(esquema)) {
                assertNotNull(in, "falta " + DIR + esquema);
                assertNotNull(dbf.newDocumentBuilder().parse(in),
                        esquema + " no es XML valido");
            }
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private static List<String> enums(String esquema, String tipo) {
        String xml = leer(esquema);
        Matcher m = Pattern
                .compile("name=\"" + Pattern.quote(tipo) + "\"[\\s\\S]*?</xs:simpleType>")
                .matcher(xml);
        if (!m.find()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        Matcher e = Pattern.compile("<xs:enumeration value=\"([^\"]+)\"").matcher(m.group());
        while (e.find()) {
            values.add(e.group(1));
        }
        return values;
    }

    private static String patronDeClave(String esquema) {
        Matcher m = Pattern
                .compile("name=\"ClaveType\"[\\s\\S]{0,800}?pattern value=\"([^\"]+)\"")
                .matcher(leer(esquema));
        return m.find() ? m.group(1) : "";
    }

    private static String leer(String esquema) {
        try (InputStream in = open(esquema)) {
            assertNotNull(in, "falta " + DIR + esquema);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("no se pudo leer " + esquema, e);
        }
    }

    private static InputStream open(String esquema) {
        return CatalogosReferenciaXsdTest.class.getResourceAsStream(DIR + esquema);
    }
}
