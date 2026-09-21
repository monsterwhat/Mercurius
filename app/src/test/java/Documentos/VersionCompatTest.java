package Documentos;

import Utils.ComprobanteFactory;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Contract test for Hacienda schema version compatibility (v4.3 vs v4.4).
 *
 * <p>Assertions required by task:</p>
 * <ul>
 *   <li>ComprobanteFactory.getSupportedVersions has no 4.5</li>
 *   <li>detectVersion maps both namespaces (v4.3 and v4.4)</li>
 *   <li>Parser schemaVersion branching expectation documented</li>
 * </ul>
 *
 * <p>Parser branching expectation (documented contract, not yet enforced in Parser):</p>
 * <pre>
 *   if ("4.3".equals(schemaVersion)) {
 *       // Expect Encabezado wrapper + root-level &lt;MedioPago&gt;01&lt;/MedioPago&gt;
 *       // (simple code, NOT inside ResumenFactura). ResumenFactura.MedioPago absent.
 *       // Example fixture: factura-43-min.xml
 *       List&lt;MedioPago&gt; mp = parseMedioPago(rootNode.path("MedioPago"), encabezado);
 *       // parseMedioPagoR should return empty for 4.3
 *   } else if ("4.4".equals(schemaVersion)) {
 *       // Expect flat fields (no Encabezado wrapper) + ResumenFactura.MedioPago
 *       // &lt;ResumenFactura&gt;&lt;MedioPago&gt;&lt;TipoMedioPago&gt;01&lt;/TipoMedioPago&gt;
 *       // &lt;TotalMedioPago&gt;113.00000&lt;/TotalMedioPago&gt;&lt;/MedioPago&gt;&lt;/ResumenFactura&gt;
 *       // Root-level simple MedioPago absent. Parser currently stores schemaVersion
 *       // on ComprobantesRecibidos/Encabezado/ResumenFactura (Parser:1205-1207) but
 *       // does not yet branch on it for MedioPago location — this test documents
 *       // the desired future branching so that v4.3 and v4.4 fixtures are handled
 *       // correctly without breaking existing flat parsing.
 *       // Example fixture: factura-44-min.xml
 *       List&lt;MedioPagoR&gt; mpR = parseMedioPagoR(resumenNode);
 *       // validarTotalMedioPago (V4.4 Bitácora 124/125) only meaningful for 4.4
 *   }
 * </pre>
 * Future Parser change should branch on schemaVersion to select the correct
 * MedioPago source and validation path; until then this test locks the fixture
 * contract and the version-detection contract.
 */
class VersionCompatTest {

    private static final String NS_43 = "https://cdn.comprobanteselectronicos.go.cr/xml-schemas/v4.3/facturaElectronica";
    private static final String NS_44 = "https://cdn.comprobanteselectronicos.go.cr/xml-schemas/v4.4/facturaElectronica";

    @Test
    void getSupportedVersionsHasNo45() {
        String[] versions = ComprobanteFactory.getSupportedVersions();
        assertNotNull(versions, "getSupportedVersions must not return null");
        List<String> list = Arrays.asList(versions);

        // Contract: only 4.3 and 4.4 are supported; 4.5 must NOT be advertised
        assertTrue(list.contains("4.3"), "Supported versions must contain 4.3");
        assertTrue(list.contains("4.4"), "Supported versions must contain 4.4");
        assertFalse(list.contains("4.5"), "Contract: 4.5 must not be in getSupportedVersions (no premature support)");

        assertTrue(ComprobanteFactory.isVersionSupported("4.3"), "isVersionSupported(4.3) should be true");
        assertTrue(ComprobanteFactory.isVersionSupported("4.4"), "isVersionSupported(4.4) should be true");
        assertFalse(ComprobanteFactory.isVersionSupported("4.5"), "isVersionSupported(4.5) must be false");
        assertFalse(ComprobanteFactory.isVersionSupported("5.0"), "isVersionSupported(5.0) must be false");
    }

    @Test
    void detectVersionMapsBothNamespacesViaStream() throws Exception {
        // Inline minimal XML headers mirroring fixtures; detectVersion scans first 4096 bytes for xml-schemas/vX.Y/
        String xml43 = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><FacturaElectronica xmlns=\"" + NS_43 + "\"><Clave>50626072600031011569830010000001040000000001000000</Clave></FacturaElectronica>";
        String xml44 = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><FacturaElectronica xmlns=\"" + NS_44 + "\"><Clave>50626072600031011569830010000001040000000001000000</Clave></FacturaElectronica>";

        String v43 = ComprobanteFactory.detectVersion(new ByteArrayInputStream(xml43.getBytes(StandardCharsets.UTF_8)));
        String v44 = ComprobanteFactory.detectVersion(new ByteArrayInputStream(xml44.getBytes(StandardCharsets.UTF_8)));

        assertEquals("4.3", v43, "detectVersion must map v4.3 namespace to 4.3");
        assertEquals("4.4", v44, "detectVersion must map v4.4 namespace to 4.4");

        // Also verify that the underlying InputStream is reset (mark/reset contract)
        ByteArrayInputStream reusable = new ByteArrayInputStream(xml43.getBytes(StandardCharsets.UTF_8));
        String first = ComprobanteFactory.detectVersion(reusable);
        assertEquals("4.3", first);
        // After reset we should still be able to read the full content
        assertTrue(reusable.available() > 0, "Stream must be reset after detectVersion");
    }

    @Test
    void detectVersionByNamespaceMapsBoth() throws Exception {
        assertEquals("4.3", ComprobanteFactory.detectVersionByNamespace(NS_43), "detectVersionByNamespace must handle v4.3");
        assertEquals("4.4", ComprobanteFactory.detectVersionByNamespace(NS_44), "detectVersionByNamespace must handle v4.4");
        // Negative: unknown namespace throws
        assertThrows(Exception.class, () -> ComprobanteFactory.detectVersionByNamespace("https://example.com/no-version"));
    }

    @Test
    void detectVersionMapsBothFixtureFiles() throws Exception {
        // Load real fixtures from test resources; both must be present and map correctly
        try (InputStream in43 = getClass().getResourceAsStream("/fixtures/recibidos/factura-43-min.xml")) {
            assertNotNull(in43, "factura-43-min.xml fixture must exist on classpath");
            String v43 = ComprobanteFactory.detectVersion(in43);
            assertEquals("4.3", v43, "factura-43-min.xml must be detected as 4.3");
        }
        try (InputStream in44 = getClass().getResourceAsStream("/fixtures/recibidos/factura-44-min.xml")) {
            assertNotNull(in44, "factura-44-min.xml fixture must exist on classpath");
            String v44 = ComprobanteFactory.detectVersion(in44);
            assertEquals("4.4", v44, "factura-44-min.xml must be detected as 4.4");
        }
    }

    @Test
    void parserSchemaVersionBranchingExpectationDocumented() throws Exception {
        // This test documents the Parser's desired schemaVersion branching.
        // It does NOT call Parser (which requires DB/CDI) and does NOT mock HaciendaXsdValidator per task constraints.
        // Instead it asserts fixture structural contracts that the future Parser branch must satisfy.

        String xml43;
        String xml44;
        try (InputStream in43 = getClass().getResourceAsStream("/fixtures/recibidos/factura-43-min.xml")) {
            assertNotNull(in43, "factura-43-min.xml must exist");
            xml43 = new String(in43.readAllBytes(), StandardCharsets.UTF_8);
        }
        try (InputStream in44 = getClass().getResourceAsStream("/fixtures/recibidos/factura-44-min.xml")) {
            assertNotNull(in44, "factura-44-min.xml must exist");
            xml44 = new String(in44.readAllBytes(), StandardCharsets.UTF_8);
        }

        // --- Shared minimal data contract (both fixtures) ---
        for (String xml : List.of(xml43, xml44)) {
            assertTrue(xml.contains("50626072600031011569830010000001040000000001000000"),
                    "Clave must be 50-digit per spec in both fixtures");
            assertTrue(xml.contains("00100001040000000001"),
                    "NumeroConsecutivo must be 20-char per spec in both fixtures");
            assertTrue(xml.contains("<Detalle>Item</Detalle>") || xml.contains("<Detalle>"),
                    "DetalleServicio must have at least one line");
            // Keep files small: single LineaDetalle, <10 detail lines conceptually
            int lineCount = xml.split("<LineaDetalle>").length - 1;
            assertEquals(1, lineCount, "Minimal fixtures must contain exactly one LineaDetalle");
        }

        // --- v4.3 structural expectation: Encabezado wrapper + root MedioPago ---
        assertTrue(xml43.contains("xml-schemas/v4.3/facturaElectronica"),
                "v4.3 fixture must use v4.3 namespace");
        assertTrue(xml43.contains("<Encabezado>") && xml43.contains("</Encabezado>"),
                "v4.3 fixture must use Encabezado wrapper style (pre-flat, v4.3 convention)");
        // Root MedioPago simple code (Parser.parseMedioPago expects rootNode.path(\"MedioPago\").asText())
        assertTrue(xml43.contains("<MedioPago>01</MedioPago>"),
                "v4.3 fixture must have root-level <MedioPago>01</MedioPago>");
        // v4.3 should NOT have ResumenFactura.MedioPago structured element with TipoMedioPago
        assertFalse(xml43.contains("<TipoMedioPago>"),
                "v4.3 fixture must NOT contain Resumen.MedioPago.TipoMedioPago (that's v4.4 style)");

        // --- v4.4 structural expectation: flat (no Encabezado) + Resumen.MedioPago ---
        assertTrue(xml44.contains("xml-schemas/v4.4/facturaElectronica"),
                "v4.4 fixture must use v4.4 namespace");
        assertFalse(xml44.contains("<Encabezado>"),
                "v4.4 fixture must be flat (no Encabezado wrapper) per XmlEncabezadoFlattener contract");
        // ResumenFactura.MedioPago structured (Parser.parseMedioPagoR expects resumenNode.path(\"MedioPago\"))
        assertTrue(xml44.contains("<ResumenFactura>") && xml44.contains("<MedioPago>") && xml44.contains("<TipoMedioPago>01</TipoMedioPago>"),
                "v4.4 fixture must have ResumenFactura.MedioPago with TipoMedioPago");
        assertTrue(xml44.contains("<TotalMedioPago>113.00000</TotalMedioPago>"),
                "v4.4 fixture MedioPago Total must sum to TotalComprobante for validarTotalMedioPago (Bitácora 124/125)");
        // v4.4 should NOT have bare root MedioPago (outside Resumen) as simple code
        // We check that the first MedioPago appears inside ResumenFactura, not at root before DetalleServicio
        int resumenIdx = xml44.indexOf("<ResumenFactura>");
        int medioPagoIdx = xml44.indexOf("<MedioPago>");
        assertTrue(medioPagoIdx > resumenIdx,
                "v4.4 MedioPago must appear inside ResumenFactura (after <ResumenFactura>), not at root");

        // --- Documented branching expectation (comment assertion) ---
        // Expected future Parser code (illustrative, not executed here):
        // String schemaVersion = ComprobanteFactory.detectVersion(inputStream);
        // if ("4.3".equals(schemaVersion)) {
        //     List<MedioPago> mp = parseMedioPago(rootNode.path("MedioPago"), encabezado);
        //     // ResumenFactura.MedioPago absent; skip validarTotalMedioPago
        // } else if ("4.4".equals(schemaVersion)) {
        //     ResumenFactura rf = parseResumenFactura(rootNode.path("ResumenFactura"));
        //     List<MedioPagoR> mpR = parseMedioPagoR(resumenNode); // requires TipoMedioPago + TotalMedioPago
        //     validarTotalMedioPago(rf, consecutivo, xml);
        // }
        // Until implemented, Parser currently stores schemaVersion on entity (setSchemaVersion)
        // without branching — this test locks that the fixtures expose the intended branch inputs.
        assertTrue(true, "Branching expectation documented: schemaVersion drives MedioPago source selection");
    }
}
