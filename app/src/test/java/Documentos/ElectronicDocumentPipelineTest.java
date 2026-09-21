package Documentos;

import Models.Detalles.DetalleServicio;
import Models.Detalles.Impuesto;
import Models.Detalles.LineaDetalle;
import Models.Encabezado.CorreoElectronicoEmisor;
import Models.Encabezado.Emisor;
import Models.Encabezado.Encabezado;
import Models.Encabezado.IdentificacionEmisor;
import Models.Encabezado.IdentificacionReceptor;
import Models.Encabezado.Receptor;
import Models.Encabezado.Ubicacion;
import Models.Resumen.CodigoTipoMoneda;
import Models.Jaxb.FE.FacturaElectronicaDocumento;
import Models.Jaxb.TE.TiqueteElectronicoDocumento;
import Models.Jaxb.NC.NotaCreditoElectronicaDocumento;
import Models.Jaxb.ND.NotaDebitoElectronicaDocumento;
import Models.Jaxb.FEC.FacturaCompraElectronicaDocumento;
import Models.Jaxb.FEE.FacturaExportacionElectronicaDocumento;
import Models.Jaxb.REP.ReciboElectronicoPagoDocumento;
import Models.Referencias.InformacionReferencia;
import Models.Resumen.ResumenFactura;
import Services.HaciendaCertificateService;
import Services.HaciendaSigner;
import Services.HaciendaXsdValidator;
import Utils.XmlEncabezadoFlattener;

import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.Marshaller;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.StringWriter;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.Security;
import java.security.cert.X509Certificate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import org.bouncycastle.asn1.x500.X500Name;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Full electronic document lifecycle tests for all 7 Hacienda v4.4 document types:
 * <ol>
 *   <li>Build JAXB entity with domain objects</li>
 *   <li>Marshal to XML via JAXB</li>
 *   <li>Flatten Encabezado wrapper via {@link XmlEncabezadoFlattener}</li>
 *   <li>Sign with XAdES-EPES via {@link HaciendaSigner}</li>
 *   <li>Verify XAdES-EPES structure (ds:Signature, xades:QualifyingProperties, SignedProperties)</li>
 * </ol>
 */
class ElectronicDocumentPipelineTest {

    private static JAXBContext feCtx, teCtx, ncCtx, ndCtx, fecCtx, feeCtx, repCtx;
    private static HaciendaSigner signer;

    // Namespaces
    private static final String FE_NS =
        "https://cdn.comprobanteselectronicos.go.cr/xml-schemas/v4.4/facturaElectronica";
    private static final String TE_NS =
        "https://cdn.comprobanteselectronicos.go.cr/xml-schemas/v4.4/tiqueteElectronico";
    private static final String NC_NS =
        "https://cdn.comprobanteselectronicos.go.cr/xml-schemas/v4.4/notaCreditoElectronica";
    private static final String ND_NS =
        "https://cdn.comprobanteselectronicos.go.cr/xml-schemas/v4.4/notaDebitoElectronica";
    private static final String FEC_NS =
        "https://cdn.comprobanteselectronicos.go.cr/xml-schemas/v4.4/facturaElectronicaCompra";
    private static final String FEE_NS =
        "https://cdn.comprobanteselectronicos.go.cr/xml-schemas/v4.4/facturaElectronicaExportacion";
    private static final String REP_NS =
        "https://cdn.comprobanteselectronicos.go.cr/xml-schemas/v4.4/reciboElectronicoPago";

    private static final String DS_NS = "http://www.w3.org/2000/09/xmldsig#";
    private static final String XADES_NS = "http://uri.etsi.org/01903/v1.3.2#";

    // Document type enum for parameterized tests
    enum DocType {
        FE, TE, NC, ND, FEC, FEE, REP
    }

    @BeforeAll
    static void setUp() throws Exception {
        // Use standalone Apache Xalan for TransformerFactory disallow-doctype-decl support
        System.setProperty("javax.xml.transform.TransformerFactory",
            "TestSupport.TestTransformerFactory");

        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }

        // Initialize JAXB contexts
        feCtx = JAXBContext.newInstance(FacturaElectronicaDocumento.class);
        teCtx = JAXBContext.newInstance(TiqueteElectronicoDocumento.class);
        ncCtx = JAXBContext.newInstance(NotaCreditoElectronicaDocumento.class);
        ndCtx = JAXBContext.newInstance(NotaDebitoElectronicaDocumento.class);
        fecCtx = JAXBContext.newInstance(FacturaCompraElectronicaDocumento.class);
        feeCtx = JAXBContext.newInstance(FacturaExportacionElectronicaDocumento.class);
        repCtx = JAXBContext.newInstance(ReciboElectronicoPagoDocumento.class);

        // Build test keystore
        KeyStore ks = buildTestKeyStore();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ks.store(bos, "testpass".toCharArray());

        // Mock certificate service
        HaciendaCertificateService mockCertService = mock(HaciendaCertificateService.class);
        KeyStore loadedKs = KeyStore.getInstance("PKCS12");
        loadedKs.load(new ByteArrayInputStream(bos.toByteArray()), "testpass".toCharArray());
        when(mockCertService.loadKeyStore()).thenReturn(loadedKs);
        when(mockCertService.getDecryptedCertificadoPassword()).thenReturn("testpass");

        // Create signer and inject mocks via reflection (different package)
        signer = new HaciendaSigner(mockCertService);

        HaciendaXsdValidator mockValidator = mock(HaciendaXsdValidator.class);
        when(mockValidator.validate(anyString(), anyString()))
            .thenReturn(HaciendaXsdValidator.ValidationResult.ok());
        setField(signer, "xsdValidator", mockValidator);

    }

    // ── Parameterized pipeline test for all 7 doc types ───────────────────

    @ParameterizedTest(name = "Pipeline: {0}")
    @EnumSource(DocType.class)
    void fullPipeline_marshalFlattenSignVerify(DocType docType) throws Exception {
        // 1. Build JAXB entity
        Object jaxbDoc = buildJaxbDoc(docType);

        // 2. Marshal to XML
        JAXBContext ctx = getJaxbContext(docType);
        String marshalledXml = marshal(ctx, jaxbDoc);
        assertNotNull(marshalledXml, "Marshalling should produce non-null XML");

        // 3. Flatten Encabezado wrapper
        String flatXml = XmlEncabezadoFlattener.flatten(marshalledXml);
        assertNotNull(flatXml, "Flattening should produce non-null XML");
        assertFalse(flatXml.contains("<Encabezado>"),
            docType + ": Encabezado wrapper must be removed after flattening");

        // 4. Sign with XAdES-EPES
        HaciendaSigner.SignResult signResult = signer.signXml(flatXml);
        assertTrue(signResult.success,
            docType + ": Signing should succeed: " + signResult.errorMessage);
        assertNotNull(signResult.signedXml);

        // 5-8. Verify XAdES-EPES structure
        Document doc = parseXml(signResult.signedXml);
        verifySignatureElement(doc, docType);
        verifyQualifyingProperties(doc, docType);
        verifySignedProperties(doc, docType);
        verifyExplicitPolicy(doc, docType);
    }

    // ── Individual document type tests (non-parameterized for clarity) ────

    @Test
    void testFE_fullPipeline() throws Exception {
        runPipelineForType(DocType.FE);
    }

    @Test
    void testTE_fullPipeline() throws Exception {
        runPipelineForType(DocType.TE);
    }

    @Test
    void testNC_fullPipeline() throws Exception {
        runPipelineForType(DocType.NC);
    }

    @Test
    void testND_fullPipeline() throws Exception {
        runPipelineForType(DocType.ND);
    }

    @Test
    void testFEC_fullPipeline() throws Exception {
        runPipelineForType(DocType.FEC);
    }

    @Test
    void testFEE_fullPipeline() throws Exception {
        runPipelineForType(DocType.FEE);
    }

    @Test
    void testREP_fullPipeline() throws Exception {
        runPipelineForType(DocType.REP);
    }

    // ── XAdES-EPES verification helpers ───────────────────────────────────

    private void verifySignatureElement(Document doc, DocType docType) {
        NodeList signatures = doc.getElementsByTagNameNS(DS_NS, "Signature");
        assertEquals(1, signatures.getLength(),
            docType + ": Must contain exactly one ds:Signature");

        String signatureId = signatures.item(0).getAttributes().getNamedItem("Id").getTextContent();
        assertTrue(signatureId.startsWith("signature-"),
            docType + ": Signature Id must start with 'signature-', was: " + signatureId);
    }

    private void verifyQualifyingProperties(Document doc, DocType docType) throws Exception {
        XPath xpath = XPathFactory.newInstance().newXPath();

        NodeList qp = (NodeList) xpath.evaluate(
            "//*[local-name()='QualifyingProperties']", doc, XPathConstants.NODESET);
        assertEquals(1, qp.getLength(), docType + ": Must have one QualifyingProperties");

        String target = qp.item(0).getAttributes().getNamedItem("Target").getTextContent();
        assertTrue(target.startsWith("#signature-"),
            docType + ": QualifyingProperties Target must start with '#signature-', was: " + target);

        // SignedInfo must have two references
        NodeList references = (NodeList) xpath.evaluate(
            "//*[local-name()='SignedInfo']/*[local-name()='Reference']",
            doc, XPathConstants.NODESET);
        assertEquals(2, references.getLength(),
            docType + ": SignedInfo must have two References (enveloped + SignedProperties)");
    }

    private void verifySignedProperties(Document doc, DocType docType) throws Exception {
        XPath xpath = XPathFactory.newInstance().newXPath();

        // SigningTime
        NodeList signingTime = (NodeList) xpath.evaluate(
            "//*[local-name()='SigningTime']", doc, XPathConstants.NODESET);
        assertEquals(1, signingTime.getLength(),
            docType + ": SignedProperties must contain SigningTime");

        // SigningCertificate
        NodeList signingCert = (NodeList) xpath.evaluate(
            "//*[local-name()='SigningCertificate']", doc, XPathConstants.NODESET);
        assertTrue(signingCert.getLength() >= 1,
            docType + ": SignedProperties must contain SigningCertificate");

        // SignaturePolicyIdentifier
        NodeList policyId = (NodeList) xpath.evaluate(
            "//*[local-name()='SignaturePolicyIdentifier']",
            doc, XPathConstants.NODESET);
        assertEquals(1, policyId.getLength(),
            docType + ": SignedProperties must contain SignaturePolicyIdentifier");

        // X509Certificate in KeyInfo
        NodeList certs = (NodeList) xpath.evaluate(
            "//*[local-name()='X509Certificate']", doc, XPathConstants.NODESET);
        assertTrue(certs.getLength() >= 1,
            docType + ": KeyInfo must contain X509Certificate");
    }

    private void verifyExplicitPolicy(Document doc, DocType docType) throws Exception {
        XPath xpath = XPathFactory.newInstance().newXPath();

        // SignaturePolicyImplied must NOT exist
        NodeList implied = (NodeList) xpath.evaluate(
            "//*[local-name()='SignaturePolicyImplied']",
            doc, XPathConstants.NODESET);
        assertEquals(0, implied.getLength(),
            docType + ": SignaturePolicyImplied must NOT exist — explicit policy required");

        // SignaturePolicyId must exist
        NodeList policyId = (NodeList) xpath.evaluate(
            "//*[local-name()='SignaturePolicyId']",
            doc, XPathConstants.NODESET);
        assertTrue(policyId.getLength() >= 1,
            docType + ": Explicit SignaturePolicyId must be present");
    }

    // ── Pipeline runner ───────────────────────────────────────────────────

    private void runPipelineForType(DocType docType) throws Exception {
        Object jaxbDoc = buildJaxbDoc(docType);
        JAXBContext ctx = getJaxbContext(docType);
        String flatXml = XmlEncabezadoFlattener.flatten(marshal(ctx, jaxbDoc));

        HaciendaSigner.SignResult result = signer.signXml(flatXml);
        assertTrue(result.success, docType + ": Signing failed: " + result.errorMessage);

        Document doc = parseXml(result.signedXml);
        verifySignatureElement(doc, docType);
        verifyQualifyingProperties(doc, docType);
        verifySignedProperties(doc, docType);
        verifyExplicitPolicy(doc, docType);
    }

    // ── Entity factory helpers (adapted from XsdModelAlignmentTest) ───────

    private static Encabezado createEncabezado(boolean isRep, boolean isFec) {
        Encabezado enc = new Encabezado();
        enc.setClave("50626072600031011569830010000001040000000001000000");
        enc.setProveedorSistemas("3101156983");
        enc.setCodigoActividadEmisor(isRep ? null : "123456");
        enc.setNumeroConsecutivo("00100001040000000001");
        enc.setFechaEmision(LocalDateTime.of(2026, 7, 2, 12, 0, 0));
        enc.setCondicionVenta(isRep ? "09" : "01");
        if (isFec) {
            enc.setCodigoActividadReceptor("654321");
        }

        Emisor emisor = new Emisor();
        emisor.setNombre("EMISOR PRUEBA S.A.");
        IdentificacionEmisor idEmisor = new IdentificacionEmisor();
        idEmisor.setTipo("02");
        idEmisor.setNumero("3101156983");
        emisor.setIdentificacion(idEmisor);
        emisor.setRegistrofiscal8707("123456789012");
        emisor.setNombreComercial("Mi Negocio S.A.");

        Ubicacion ubicacion = new Ubicacion();
        ubicacion.setProvincia("1");
        ubicacion.setCanton("01");
        ubicacion.setDistrito("01");
        ubicacion.setOtrasSenas("Direccion de prueba");
        emisor.setUbicacion(ubicacion);

        CorreoElectronicoEmisor email = new CorreoElectronicoEmisor();
        email.setCorreo("emisor@prueba.com");
        emisor.setCorreosElectronicos(List.of(email));

        enc.setEmisor(emisor);

        Receptor receptor = new Receptor();
        receptor.setNombre("RECEPTOR PRUEBA S.A.");
        IdentificacionReceptor idReceptor = new IdentificacionReceptor();
        idReceptor.setTipo("02");
        idReceptor.setNumero("3101156984");
        receptor.setIdentificacion(idReceptor);
        enc.setReceptor(receptor);

        return enc;
    }

    private static DetalleServicio createDetalleServicio(String variant) {
        boolean isRep = "REP".equals(variant);
        boolean isFee = "FEE".equals(variant);
        boolean isFull = "FULL".equals(variant);

        LineaDetalle linea = new LineaDetalle();
        linea.setNumeroLinea(1);

        if (!isRep) {
            linea.setCodigoCabys("1234567890123");
            linea.setCantidad(new BigDecimal("1.000"));
            linea.setUnidadMedida("Unid");
            linea.setPrecioUnitario(new BigDecimal("100.00000"));
        }
        linea.setDetalle("Item de prueba");
        linea.setMontoTotal(new BigDecimal("100.00000"));
        linea.setSubTotal(new BigDecimal("100.00000"));
        if (!isRep && !isFee) {
            linea.setBaseImponible(new BigDecimal("100.00000"));
        }
        if (!isFee) {
            linea.setImpuestoNeto(new BigDecimal("13.00000"));
        }
        linea.setMontoTotalLinea(new BigDecimal("113.00000"));
        if (isFull) {
            linea.setImpuestoAsumidoEmisorFabrica(new BigDecimal("13.00000"));
        }

        Impuesto impuesto = new Impuesto();
        impuesto.setCodigo("01");
        impuesto.setCodigoTarifaIVA("08");
        impuesto.setTarifa(new BigDecimal("13.00"));
        impuesto.setMonto(new BigDecimal("13.00000"));
        linea.setImpuestos(List.of(impuesto));

        DetalleServicio ds = new DetalleServicio();
        ds.setLineasDetalle(List.of(linea));
        return ds;
    }

    private static ResumenFactura createResumen() {
        ResumenFactura r = new ResumenFactura();
        CodigoTipoMoneda moneda = new CodigoTipoMoneda();
        moneda.setCodigoMoneda("CRC");
        r.setCodigoMoneda(moneda);
        r.setTotalVenta(new BigDecimal("100.00000"));
        r.setTotalVentaNeta(new BigDecimal("100.00000"));
        r.setTotalImpuesto(new BigDecimal("13.00000"));
        r.setTotalComprobante(new BigDecimal("113.00000"));
        return r;
    }

    private static List<InformacionReferencia> createInfoReferencia() {
        InformacionReferencia ref = new InformacionReferencia();
        ref.setTipoDoc("01");
        ref.setNumero("00100001000000000001");
        ref.setFechaEmision(LocalDateTime.of(2026, 7, 2, 10, 0, 0));
        ref.setCodigo("13");
        ref.setRazon("Referencia de prueba");
        ref.setTipoDocRefOTRO(null);
        ref.setCodigoReferenciaOTRO(null);
        return List.of(ref);
    }

    // ── JAXB document builders ────────────────────────────────────────────

    private static Object buildJaxbDoc(DocType type) {
        Encabezado enc;
        DetalleServicio ds;
        ResumenFactura res;
        List<InformacionReferencia> refs = createInfoReferencia();

        switch (type) {
            case FE -> {
                enc = createEncabezado(false, false);
                ds = createDetalleServicio("FULL");
                res = createResumen();
                var doc = new FacturaElectronicaDocumento();
                doc.setEncabezado(new Models.Jaxb.FE.Encabezado(enc));
                doc.setDetalleServicio(new Models.Jaxb.FE.DetalleServicio(ds));
                doc.setResumen(new Models.Jaxb.FE.ResumenFactura(res));
                doc.setInformacionReferencia(refs.stream()
                    .map(Models.Jaxb.FE.InformacionReferencia::new).collect(Collectors.toList()));
                return doc;
            }
            case TE -> {
                enc = createEncabezado(false, false);
                ds = createDetalleServicio("FULL");
                res = createResumen();
                var doc = new TiqueteElectronicoDocumento();
                doc.setEncabezado(new Models.Jaxb.TE.Encabezado(enc));
                doc.setDetalleServicio(new Models.Jaxb.TE.DetalleServicio(ds));
                doc.setResumen(new Models.Jaxb.TE.ResumenFactura(res));
                doc.setInformacionReferencia(refs.stream()
                    .map(Models.Jaxb.TE.InformacionReferencia::new).collect(Collectors.toList()));
                return doc;
            }
            case NC -> {
                enc = createEncabezado(false, false);
                ds = createDetalleServicio("STD");
                res = createResumen();
                var doc = new NotaCreditoElectronicaDocumento();
                doc.setEncabezado(new Models.Jaxb.NC.Encabezado(enc));
                doc.setDetalleServicio(new Models.Jaxb.NC.DetalleServicio(ds));
                doc.setResumen(new Models.Jaxb.NC.ResumenFactura(res));
                doc.setInformacionReferencia(refs.stream()
                    .map(Models.Jaxb.NC.InformacionReferencia::new).collect(Collectors.toList()));
                return doc;
            }
            case ND -> {
                enc = createEncabezado(false, false);
                ds = createDetalleServicio("STD");
                res = createResumen();
                var doc = new NotaDebitoElectronicaDocumento();
                doc.setEncabezado(new Models.Jaxb.ND.Encabezado(enc));
                doc.setDetalleServicio(new Models.Jaxb.ND.DetalleServicio(ds));
                doc.setResumen(new Models.Jaxb.ND.ResumenFactura(res));
                doc.setInformacionReferencia(refs.stream()
                    .map(Models.Jaxb.ND.InformacionReferencia::new).collect(Collectors.toList()));
                return doc;
            }
            case FEC -> {
                enc = createEncabezado(false, true);
                ds = createDetalleServicio("STD");
                res = createResumen();
                var doc = new FacturaCompraElectronicaDocumento();
                doc.setEncabezado(new Models.Jaxb.FEC.Encabezado(enc));
                doc.setDetalleServicio(new Models.Jaxb.FEC.DetalleServicio(ds));
                doc.setResumen(new Models.Jaxb.FEC.ResumenFactura(res));
                doc.setInformacionReferencia(refs.stream()
                    .map(Models.Jaxb.FEC.InformacionReferencia::new).collect(Collectors.toList()));
                return doc;
            }
            case FEE -> {
                enc = createEncabezado(false, false);
                ds = createDetalleServicio("FEE");
                res = createResumen();
                var doc = new FacturaExportacionElectronicaDocumento();
                doc.setEncabezado(new Models.Jaxb.FEE.Encabezado(enc));
                doc.setDetalleServicio(new Models.Jaxb.FEE.DetalleServicio(ds));
                doc.setResumen(new Models.Jaxb.FEE.ResumenFactura(res));
                doc.setInformacionReferencia(refs.stream()
                    .map(Models.Jaxb.FEE.InformacionReferencia::new).collect(Collectors.toList()));
                return doc;
            }
            case REP -> {
                enc = createEncabezado(true, false);
                ds = createDetalleServicio("REP");
                res = createResumen();
                var doc = new ReciboElectronicoPagoDocumento();
                doc.setEncabezado(new Models.Jaxb.REP.Encabezado(enc));
                doc.setDetalleServicio(new Models.Jaxb.REP.DetalleServicio(ds));
                doc.setResumen(new Models.Jaxb.REP.ResumenFactura(res));
                doc.setInformacionReferencia(refs.stream()
                    .map(Models.Jaxb.REP.InformacionReferencia::new).collect(Collectors.toList()));
                return doc;
            }
            default -> throw new IllegalArgumentException("Unknown DocType: " + type);
        }
    }

    private static JAXBContext getJaxbContext(DocType type) {
        return switch (type) {
            case FE -> feCtx;
            case TE -> teCtx;
            case NC -> ncCtx;
            case ND -> ndCtx;
            case FEC -> fecCtx;
            case FEE -> feeCtx;
            case REP -> repCtx;
        };
    }

    // ── Utility methods ───────────────────────────────────────────────────

    private static String marshal(JAXBContext ctx, Object doc) throws Exception {
        Marshaller m = ctx.createMarshaller();
        m.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, Boolean.TRUE);
        StringWriter sw = new StringWriter();
        m.marshal(doc, sw);
        return sw.toString();
    }

    private static Document parseXml(String xml) throws Exception {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(true);
        DocumentBuilder db = dbf.newDocumentBuilder();
        return db.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    private static KeyStore buildTestKeyStore() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        KeyPair kp = kpg.generateKeyPair();

        JcaX509v3CertificateBuilder certBuilder = new JcaX509v3CertificateBuilder(
            new X500Name("CN=Test CA, O=Mercurius Test"),
            java.math.BigInteger.valueOf(System.currentTimeMillis()),
            new java.util.Date(System.currentTimeMillis() - 86400000L),
            new java.util.Date(System.currentTimeMillis() + 86400000L * 365),
            new X500Name("CN=Test Signer, O=Mercurius Test"),
            kp.getPublic());

        ContentSigner cs = new JcaContentSignerBuilder("SHA256withRSA").build(kp.getPrivate());
        X509CertificateHolder certHolder = certBuilder.build(cs);
        X509Certificate cert = new JcaX509CertificateConverter().getCertificate(certHolder);

        KeyStore ks = KeyStore.getInstance("PKCS12");
        ks.load(null, null);
        ks.setKeyEntry("test", kp.getPrivate(), "testpass".toCharArray(), new X509Certificate[]{cert});
        return ks;
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
