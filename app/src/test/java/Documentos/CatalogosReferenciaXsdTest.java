package Documentos;

import Models.Enums.Tipo_CodigosReferencia;
import Models.Enums.Tipo_Documento_Referencia;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.xml.XMLConstants;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The catalog codes the app can EMIT must be codes the schema it validates
 * against ACCEPTS.
 *
 * <p>This gap was real and shipped: {@code Tipo_Documento_Referencia} already
 * offered {@code 19} (Factura Electrónica de Exportación) and {@code 20}
 * (Recibo Electrónico de Pago) - the codes the DGT added to the Anexos on
 * 2026-04-22, mandatory from 2026-11-01 - while the bundled
 * {@code FacturaElectronica_V4.4.xsd} still enumerated only up to {@code 18}.
 * The app could therefore build a document its own validator rejected. Codes
 * {@code 13}-{@code 15} were separately hand-pasted into the official XSD with
 * placeholder descriptions and no record of where they came from.</p>
 *
 * <p>Both sets now live side by side: {@code /xsd/v4.4/} pristine from
 * Hacienda, {@code /xsd/overlay/v4.4-202611/} plus the pending codes. See
 * {@code xsd/overlay/v4.4-202611/MANIFIEST.md}.</p>
 *
 * <p><b>No database, no HTTP, no Quarkus.</b> Plain JUnit: it reads the XSDs off
 * the classpath and compares enumerations.</p>
 */
class CatalogosReferenciaXsdTest {

    private static final String OFFICIAL = "/xsd/v4.4/";
    private static final String OVERLAY = "/xsd/overlay/v4.4-202611/";

    /** Documents whose InformacionReferencia carries both catalogs. */
    private static final List<String> SCHEMAS = List.of(
            "FacturaElectronica_V4.4.xsd",
            "NotaCreditoElectronica_V4.4.xsd",
            "NotaDebitoElectronica_V4.4.xsd",
            "TiqueteElectronico_V4.4.xsd",
            "FacturaElectronicaCompra_V4.4.xsd",
            "FacturaElectronicaExportacion_V4.4.xsd",
            "ReciboElectronicoPago_V4.4.xsd");

    // ── the regression this suite exists for ───────────────────────────────

    @Test
    @DisplayName("every Tipo_Documento_Referencia code is accepted by the overlay the validator loads")
    void todosLosCodigosDeTipoDocumentoReferenciaEstanEnElOverlay() {
        List<String> faltantes = new ArrayList<>();
        for (Tipo_Documento_Referencia v : Tipo_Documento_Referencia.values()) {
            for (String esquema : SCHEMAS) {
                if (!enumeracionesDe(esquema, "TipoDocReferenciaType", OVERLAY).contains(v.getCodigo())) {
                    faltantes.add(v.getCodigo() + " ausente de " + esquema);
                }
            }
        }
        assertTrue(faltantes.isEmpty(),
                "el enum ofrece codigos que el overlay rechaza: " + faltantes);
    }

    @Test
    @DisplayName("every Tipo_CodigosReferencia code is accepted by the overlay the validator loads")
    void todosLosCodigosDeReferenciaEstanEnElOverlay() {
        List<String> faltantes = new ArrayList<>();
        for (Tipo_CodigosReferencia v : Tipo_CodigosReferencia.values()) {
            for (String esquema : SCHEMAS) {
                if (!enumeracionesDe(esquema, "CodigoReferenciaType", OVERLAY).contains(v.getCodigo())) {
                    faltantes.add(v.getCodigo() + " ausente de " + esquema);
                }
            }
        }
        assertTrue(faltantes.isEmpty(),
                "el enum ofrece codigos que el overlay rechaza: " + faltantes);
    }

    // ── the two sets must stay distinguishable ─────────────────────────────

    @Test
    @DisplayName("the official files are untouched: they still lack the 2026 codes")
    void losArchivosOficialesNoContienenLosCodigosPendientes() {
        // This is the point of the split. If Hacienda republishes the XSDs with
        // the catalogs corrected, this test fails and the overlay should be
        // deleted rather than kept - see MANIFIEST.md.
        for (String esquema : SCHEMAS) {
            List<String> oficial = enumeracionesDe(esquema, "TipoDocReferenciaType", OFFICIAL);
            assertFalse(oficial.contains("19"),
                    esquema + ": el XSD oficial ya incluye el 19. Likely Hacienda republished "
                            + "the schemas - re-diff and drop the overlay.");
            assertFalse(oficial.contains("20"),
                    esquema + ": el XSD oficial ya incluye el 20. Likely Hacienda republished "
                            + "the schemas - re-diff and drop the overlay.");
        }
    }

    @Test
    @DisplayName("the overlay differs from the official copy only in the two catalog types")
    void elOverlaySoloAgregaLosCatalogos() {
        for (String esquema : SCHEMAS) {
            assertFalse(enumeracionesDe(esquema, "TipoDocReferenciaType", OFFICIAL)
                            .equals(enumeracionesDe(esquema, "TipoDocReferenciaType", OVERLAY)),
                    esquema + ": se esperaba que el overlay agregara 19 y 20 a TipoDocReferenciaType");
            assertFalse(enumeracionesDe(esquema, "CodigoReferenciaType", OFFICIAL)
                            .equals(enumeracionesDe(esquema, "CodigoReferenciaType", OVERLAY)),
                    esquema + ": se esperaba que el overlay agregara 13-17 a CodigoReferenciaType");
        }
    }

    // ── the overlay must be a loadable schema, not just matching text ──────

    @Test
    @DisplayName("every overlay schema compiles as a real XML Schema")
    void elOverlayCompilaComoEsquemaXml() throws Exception {
        SchemaFactory factory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
        for (String esquema : SCHEMAS) {
            // The systemId matters: the v4.4 schemas import
            // ../../xmldsig-core-schema.xsd, and a StreamSource without a base
            // URI cannot resolve it ("Cannot resolve the name 'ds:Signature'").
            // The overlay sits at the same depth as /xsd/v4.4/, so the
            // relative path resolves exactly as it does in production.
            URL url = CatalogosReferenciaXsdTest.class.getResource(OVERLAY + esquema);
            assertNotNull(url, "falta el overlay " + OVERLAY + esquema);
            try (InputStream in = url.openStream()) {
                // Compiling is the assertion: a malformed overlay would throw here
                // rather than at validation time in production.
                factory.newSchema(new StreamSource(in, url.toExternalForm()));
            }
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private static InputStream open(String path) {
        return CatalogosReferenciaXsdTest.class.getResourceAsStream(path);
    }

    /** Enumeration values of one simpleType inside one bundled schema. */
    private static List<String> enumeracionesDe(String esquema, String tipo, String dir) {
        try (InputStream in = open(dir + esquema)) {
            assertNotNull(in, "falta el esquema " + dir + esquema);
            String xml = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            Matcher typeMatcher = Pattern
                    .compile("name=\"" + Pattern.quote(tipo) + "\"[\\s\\S]*?</xs:simpleType>")
                    .matcher(xml);
            assertTrue(typeMatcher.find(), esquema + " no declara " + tipo);
            List<String> values = new ArrayList<>();
            Matcher enumMatcher = Pattern
                    .compile("<xs:enumeration value=\"([^\"]+)\"")
                    .matcher(typeMatcher.group());
            while (enumMatcher.find()) {
                values.add(enumMatcher.group(1));
            }
            return values;
        } catch (java.io.IOException e) {
            throw new IllegalStateException("no se pudo leer " + dir + esquema, e);
        }
    }
}
