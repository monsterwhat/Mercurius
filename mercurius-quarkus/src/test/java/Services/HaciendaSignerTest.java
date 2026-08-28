package Services;

import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.Security;
import java.security.cert.X509Certificate;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Date;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link HaciendaSigner}: check digit calculation,
 * invoice key generation, and XML signing with XAdES-EPES structure.
 * <p>
 * Uses a test P12 certificate generated in-memory via BouncyCastle.
 * The XSD validator is mocked to bypass schema validation so signing
 * can be tested independently of XSD compliance.
 */
class HaciendaSignerTest {

    private static byte[] testKeystoreBytes;
    private static final String KEYSTORE_PASSWORD = "testpass";
    private static HaciendaSigner signer;

    @BeforeAll
    static void generateTestKeystore() throws Exception {
        // Use standalone Apache Xalan for TransformerFactory disallow-doctype-decl support
        System.setProperty("javax.xml.transform.TransformerFactory",
            "TestSupport.TestTransformerFactory");

        // Register BouncyCastle provider for xades4j
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }

        // Generate RSA key pair
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        KeyPair kp = kpg.generateKeyPair();

        // Build self-signed X509 certificate
        JcaX509v3CertificateBuilder certBuilder = new JcaX509v3CertificateBuilder(
            new org.bouncycastle.asn1.x500.X500Name("CN=Test CA, O=Mercurius Test"),
            BigInteger.valueOf(System.currentTimeMillis()),
            new Date(System.currentTimeMillis() - 86400000L),
            new Date(System.currentTimeMillis() + 86400000L * 365),
            new org.bouncycastle.asn1.x500.X500Name("CN=Test Signer, O=Mercurius Test"),
            kp.getPublic());

        ContentSigner cs = new JcaContentSignerBuilder("SHA256withRSA").build(kp.getPrivate());
        X509CertificateHolder certHolder = certBuilder.build(cs);
        X509Certificate cert = new JcaX509CertificateConverter().getCertificate(certHolder);

        // Store in PKCS12 keystore
        KeyStore ks = KeyStore.getInstance("PKCS12");
        ks.load(null, null);
        ks.setKeyEntry("test", kp.getPrivate(), KEYSTORE_PASSWORD.toCharArray(), new X509Certificate[]{cert});

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ks.store(bos, KEYSTORE_PASSWORD.toCharArray());
        testKeystoreBytes = bos.toByteArray();

        // Build HaciendaSigner with mocked dependencies
        HaciendaCertificateService mockCertService = mock(HaciendaCertificateService.class);
        KeyStore loadedKs = KeyStore.getInstance("PKCS12");
        loadedKs.load(new ByteArrayInputStream(testKeystoreBytes), KEYSTORE_PASSWORD.toCharArray());
        when(mockCertService.loadKeyStore()).thenReturn(loadedKs);
        when(mockCertService.getDecryptedCertificadoPassword()).thenReturn(KEYSTORE_PASSWORD);

        signer = new HaciendaSigner(mockCertService);

        // Mock XSD validator to return valid (bypass schema validation for unit tests)
        HaciendaXsdValidator mockValidator = mock(HaciendaXsdValidator.class);
        when(mockValidator.validate(anyString(), anyString()))
            .thenReturn(HaciendaXsdValidator.ValidationResult.ok());
        // Package-private field access (same package)
        signer.xsdValidator = mockValidator;

        // Mock alertas service (no-op)
    }

    // ── calcularDigitoVerificador ─────────────────────────────────────────

    @Test
    void testCheckDigit_allZeros_returnsZero() {
        String prefix = "0".repeat(49);
        int result = HaciendaSigner.calcularDigitoVerificador(prefix);
        assertEquals(0, result, "All-zero prefix should produce check digit 0");
    }

    @Test
    void testCheckDigit_knownPrefix_returnsExpected() {
        // Prefix derived from a known clave structure
        String prefix = "5062607260003101156983001000000104000000000100000";
        assertEquals(49, prefix.length(), "Prefix must be exactly 49 digits");

        // Compute expected check digit using the modulo-10 algorithm independently
        int sum = 0;
        boolean weightTwo = true;
        for (int i = prefix.length() - 1; i >= 0; i--) {
            int digit = Character.getNumericValue(prefix.charAt(i));
            int product = digit * (weightTwo ? 2 : 1);
            sum += product > 9 ? product - 9 : product;
            weightTwo = !weightTwo;
        }
        int expected = (10 - (sum % 10)) % 10;

        int actual = HaciendaSigner.calcularDigitoVerificador(prefix);
        assertEquals(expected, actual, "Check digit should match independently computed value");
        assertTrue(actual >= 0 && actual <= 9, "Check digit must be a single digit 0-9");
    }

    @Test
    void testCheckDigit_rejectsNull() {
        assertThrows(IllegalArgumentException.class,
            () -> HaciendaSigner.calcularDigitoVerificador(null),
            "Null input should throw IllegalArgumentException");
    }

    @Test
    void testCheckDigit_rejectsWrongLength() {
        assertThrows(IllegalArgumentException.class,
            () -> HaciendaSigner.calcularDigitoVerificador("12345"),
            "49-char prefix shorter than 49 digits should throw");
    }

    @Test
    void testCheckDigit_rejectsTooLong() {
        assertThrows(IllegalArgumentException.class,
            () -> HaciendaSigner.calcularDigitoVerificador("0".repeat(50)),
            "Prefix longer than 49 digits should throw");
    }

    @Test
    void testCheckDigit_rejectsNonNumeric() {
        assertThrows(IllegalArgumentException.class,
            () -> HaciendaSigner.calcularDigitoVerificador("506010118000310115698300100000010400000000010000A"),
            "Non-digit characters should throw");
    }

    // ── generateInvoiceKey ────────────────────────────────────────────────

    @Test
    void testGenerateKey_isExactly50Digits() {
        String key = signer.generateInvoiceKey("3101156983", "00100001040000000001", "1",
            LocalDate.of(2026, 7, 2));
        assertEquals(50, key.length(), "Clave must be exactly 50 digits");
        assertTrue(key.matches("\\d{50}"), "Clave must contain only digits");
    }

    @Test
    void testGenerateKey_startsWithCostaRicaCountryCode() {
        String key = signer.generateInvoiceKey("3101156983", "00100001040000000001", "1",
            LocalDate.of(2026, 7, 2));
        assertTrue(key.startsWith("506"), "Clave must start with Costa Rica code 506");
    }

    @Test
    void testGenerateKey_checkDigitIsValid() {
        String key = signer.generateInvoiceKey("3101156983", "00100001040000000001", "1",
            LocalDate.of(2026, 7, 2));
        String prefix = key.substring(0, 49);
        int expectedCheckDigit = HaciendaSigner.calcularDigitoVerificador(prefix);
        int actualCheckDigit = Character.getNumericValue(key.charAt(49));
        assertEquals(expectedCheckDigit, actualCheckDigit,
            "Check digit (position 50) must match calcularDigitoVerificador(prefix)");
    }

    @Test
    void testGenerateKey_dateComponentsEmbedded() {
        LocalDate fecha = LocalDate.of(2026, 7, 2);
        String key = signer.generateInvoiceKey("3101156983", "00100001040000000001", "1", fecha);

        // Country code: positions 1-3 → "506"
        assertEquals("506", key.substring(0, 3));
        // Day: positions 4-5 → "02"
        assertEquals("02", key.substring(3, 5));
        // Month: positions 6-7 → "07"
        assertEquals("07", key.substring(5, 7));
        // Year (2-digit): positions 8-9 → "26"
        assertEquals("26", key.substring(7, 9));
    }

    @Test
    void testGenerateKey_identificationAndConsecutivePadded() {
        String key = signer.generateInvoiceKey("3101156983", "00100001040000000001", "1",
            LocalDate.of(2026, 7, 2));
        // Identification: positions 10-21 (12 digits, zero-padded)
        String idSection = key.substring(9, 21);
        assertEquals("003101156983", idSection, "Identification number should be zero-padded to 12 digits");
        // Consecutive: positions 22-41 (20 digits)
        String consecSection = key.substring(21, 41);
        assertEquals("00100001040000000001", consecSection, "Consecutive number should be zero-padded to 20 digits");
    }

    @Test
    void testGenerateKey_situationDigit() {
        String key1 = signer.generateInvoiceKey("123", "456", "1", LocalDate.now());
        String key2 = signer.generateInvoiceKey("123", "456", "2", LocalDate.now());
        // Situation is at position 42 (index 41)
        assertEquals('1', key1.charAt(41), "Situation '1' should place '1' at position 42");
        assertEquals('2', key2.charAt(41), "Situation '2' should place '2' at position 42");
    }

    @Test
    void testGenerateKey_nullSituationDefaultsToOne() {
        String key = signer.generateInvoiceKey("123", "456", null, LocalDate.now());
        assertEquals('1', key.charAt(41), "Null situation should default to '1'");
    }

    @Test
    void testGenerateKey_randomSecurityCodeIs7Digits() {
        // Positions 42-48 (indices 41-47) are the 7 random security digits
        String key = signer.generateInvoiceKey("3101156983", "00100001040000000001", "1",
            LocalDate.of(2026, 7, 2));
        String securityCode = key.substring(41, 48);
        assertEquals(7, securityCode.length(), "Security code must be 7 digits");
        assertTrue(securityCode.matches("\\d{7}"), "Security code must be numeric");
    }

    // ── signXml ───────────────────────────────────────────────────────────

    /**
     * Minimal FacturaElectronica XML with FE namespace.
     * Does NOT need to pass XSD validation (the validator is mocked).
     */
    private static final String TEST_FE_XML = """
        <?xml version="1.0" encoding="UTF-8"?>
        <FacturaElectronica xmlns="https://cdn.comprobanteselectronicos.go.cr/xml-schemas/v4.4/facturaElectronica">
            <Clave>50626072600031011569830010000001040000000001000000</Clave>
            <NumeroConsecutivo>00100001040000000001</NumeroConsecutivo>
            <FechaEmision>2026-07-02T12:00:00</FechaEmision>
            <CondicionVenta>01</CondicionVenta>
            <Emisor>
                <Nombre>Test</Nombre>
                <Identificacion>
                    <Tipo>01</Tipo>
                    <Numero>123456789</Numero>
                </Identificacion>
                <Ubicacion>
                    <Provincia>1</Provincia>
                    <Canton>01</Canton>
                    <Distrito>01</Distrito>
                    <OtrasSenas>X</OtrasSenas>
                </Ubicacion>
                <CorreoElectronico>
                    <Correo>test@test.com</Correo>
                </CorreoElectronico>
            </Emisor>
            <Receptor>
                <Nombre>Test</Nombre>
                <Identificacion>
                    <Tipo>01</Tipo>
                    <Numero>987654321</Numero>
                </Identificacion>
            </Receptor>
            <DetalleServicio>
                <LineaDetalle>
                    <NumeroLinea>1</NumeroLinea>
                    <CodigoCABYS>1234567890123</CodigoCABYS>
                    <Cantidad>1.000</Cantidad>
                    <UnidadMedida>Unid</UnidadMedida>
                    <Detalle>Item</Detalle>
                    <PrecioUnitario>100.00000</PrecioUnitario>
                    <MontoTotal>100.00000</MontoTotal>
                    <SubTotal>100.00000</SubTotal>
                    <BaseImponible>100.00000</BaseImponible>
                    <Impuesto>
                        <Codigo>01</Codigo>
                        <CodigoTarifaIVA>08</CodigoTarifaIVA>
                        <Tarifa>13.00</Tarifa>
                        <Monto>13.00000</Monto>
                    </Impuesto>
                    <ImpuestoAsumidoEmisorFabrica>13.00000</ImpuestoAsumidoEmisorFabrica>
                    <ImpuestoNeto>13.00000</ImpuestoNeto>
                    <MontoTotalLinea>113.00000</MontoTotalLinea>
                </LineaDetalle>
            </DetalleServicio>
            <ResumenFactura>
                <CodigoTipoMoneda>
                    <CodigoMoneda>CRC</CodigoMoneda>
                </CodigoTipoMoneda>
                <TotalVenta>100.00000</TotalVenta>
                <TotalVentaNeta>100.00000</TotalVentaNeta>
                <TotalImpuesto>13.00000</TotalImpuesto>
                <TotalComprobante>113.00000</TotalComprobante>
            </ResumenFactura>
        </FacturaElectronica>
        """;

    @Test
    void testSignXml_returnsSuccess() {
        HaciendaSigner.SignResult result = signer.signXml(TEST_FE_XML);
        assertTrue(result.success, "Signing should succeed: " + result.errorMessage);
        assertNotNull(result.signedXml, "Signed XML should not be null");
    }

    @Test
    void testSignXml_signedXmlContainsSignatureElement() throws Exception {
        HaciendaSigner.SignResult result = signer.signXml(TEST_FE_XML);
        assertTrue(result.success, "Signing should succeed");

        Document doc = parseXml(result.signedXml);
        NodeList signatures = doc.getElementsByTagNameNS(
            "http://www.w3.org/2000/09/xmldsig#", "Signature");
        assertEquals(1, signatures.getLength(),
            "Signed XML should contain exactly one ds:Signature element");
    }

    @Test
    void testSignXml_signatureHasCorrectId() throws Exception {
        HaciendaSigner.SignResult result = signer.signXml(TEST_FE_XML);
        assertTrue(result.success);

        Document doc = parseXml(result.signedXml);
        NodeList signatures = doc.getElementsByTagNameNS(
            "http://www.w3.org/2000/09/xmldsig#", "Signature");
        String signatureId = signatures.item(0).getAttributes().getNamedItem("Id").getTextContent();
        assertTrue(signatureId.startsWith("signature-"),
            "Signature Id must start with 'signature-', was: " + signatureId);
    }

    @Test
    void testSignXml_signedInfoHasTwoReferences() throws Exception {
        HaciendaSigner.SignResult result = signer.signXml(TEST_FE_XML);
        assertTrue(result.success);

        Document doc = parseXml(result.signedXml);
        XPath xpath = XPathFactory.newInstance().newXPath();
        NodeList references = (NodeList) xpath.evaluate(
            "//*[local-name()='SignedInfo']/*[local-name()='Reference']",
            doc, XPathConstants.NODESET);
        assertEquals(2, references.getLength(),
            "SignedInfo should contain two References (enveloped + SignedProperties)");
    }

    @Test
    void testSignXml_keyInfoContainsX509Certificate() throws Exception {
        HaciendaSigner.SignResult result = signer.signXml(TEST_FE_XML);
        assertTrue(result.success);

        Document doc = parseXml(result.signedXml);
        XPath xpath = XPathFactory.newInstance().newXPath();
        NodeList certs = (NodeList) xpath.evaluate(
            "//*[local-name()='X509Certificate']",
            doc, XPathConstants.NODESET);
        assertTrue(certs.getLength() >= 1,
            "KeyInfo should contain at least one X509Certificate");
    }

    @Test
    void testSignXml_qualifyingPropertiesExists() throws Exception {
        HaciendaSigner.SignResult result = signer.signXml(TEST_FE_XML);
        assertTrue(result.success);

        Document doc = parseXml(result.signedXml);
        XPath xpath = XPathFactory.newInstance().newXPath();
        NodeList qp = (NodeList) xpath.evaluate(
            "//*[local-name()='QualifyingProperties']",
            doc, XPathConstants.NODESET);
        assertEquals(1, qp.getLength(), "Should have exactly one QualifyingProperties element");

        String target = qp.item(0).getAttributes().getNamedItem("Target").getTextContent();
        assertTrue(target.startsWith("#signature-"),
            "QualifyingProperties Target must start with '#signature-', was: " + target);
    }

    @Test
    void testSignXml_signedPropertiesContainsRequiredElements() throws Exception {
        HaciendaSigner.SignResult result = signer.signXml(TEST_FE_XML);
        assertTrue(result.success);

        Document doc = parseXml(result.signedXml);
        XPath xpath = XPathFactory.newInstance().newXPath();

        // SigningTime
        NodeList signingTime = (NodeList) xpath.evaluate(
            "//*[local-name()='SigningTime']", doc, XPathConstants.NODESET);
        assertEquals(1, signingTime.getLength(), "SignedProperties must contain SigningTime");

        // SigningCertificate
        NodeList signingCert = (NodeList) xpath.evaluate(
            "//*[local-name()='SigningCertificate']", doc, XPathConstants.NODESET);
        assertTrue(signingCert.getLength() >= 1, "SignedProperties must contain SigningCertificate");

        // SignaturePolicyIdentifier
        NodeList policyId = (NodeList) xpath.evaluate(
            "//*[local-name()='SignaturePolicyIdentifier']",
            doc, XPathConstants.NODESET);
        assertEquals(1, policyId.getLength(),
            "SignedProperties must contain SignaturePolicyIdentifier");
    }

    @Test
    void testSignXml_explicitPolicyNotImplied() throws Exception {
        HaciendaSigner.SignResult result = signer.signXml(TEST_FE_XML);
        assertTrue(result.success);

        Document doc = parseXml(result.signedXml);
        XPath xpath = XPathFactory.newInstance().newXPath();

        // SignaturePolicyImplied should NOT exist (we use explicit policy)
        NodeList implied = (NodeList) xpath.evaluate(
            "//*[local-name()='SignaturePolicyImplied']",
            doc, XPathConstants.NODESET);
        assertEquals(0, implied.getLength(),
            "SignaturePolicyImplied must NOT exist — explicit policy is required");

        // SignaturePolicyId should exist (explicit policy identifier)
        NodeList policyId = (NodeList) xpath.evaluate(
            "//*[local-name()='SignaturePolicyId']",
            doc, XPathConstants.NODESET);
        assertTrue(policyId.getLength() >= 1,
            "Explicit SignaturePolicyId must be present");
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private static Document parseXml(String xml) throws Exception {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(true);
        DocumentBuilder db = dbf.newDocumentBuilder();
        return db.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }
}
