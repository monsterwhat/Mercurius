package Documentos;

import Models.Clientes;
import Models.ComprobantesEmitidos;
import Models.ConfiguracionAplicacion;
import Models.Detalles.DetalleServicio;
import Models.Detalles.Impuesto;
import Models.Detalles.LineaDetalle;
import Models.Encabezado.Encabezado;
import Models.Resumen.CodigoTipoMoneda;
import Models.Resumen.MedioPagoR;
import Models.Resumen.ResumenFactura;
import Services.Facturas.EmisorService;
import Services.Facturas.ReceptorService;
import Services.HaciendaXsdValidator;
import Services.Strategies.DocumentoStrategy;
import Services.Strategies.EncabezadoBuilder;
import Services.Strategies.FacturaElectronicaStrategy;
import Services.Strategies.ReciboElectronicoPagoStrategy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import javax.xml.parsers.DocumentBuilderFactory;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Characterization of the EMISSION side of {@code ProveedorSistemas} — the
 * field the Hacienda v4.4 schema declares REQUIRED at the document root,
 * immediately after {@code Clave}, with {@code maxLength 20}.
 *
 * <p><b>Why this suite exists.</b> {@code Parser} (the reception side) started
 * reading {@code ProveedorSistemas} in this session and is covered there. The
 * emission side had NO coverage at all: nothing asserted that
 * {@code ConfiguracionAplicacion.getProvedor()} is ever non-empty, so a shop
 * that never filled the setting in would emit schema-invalid documents with
 * nothing to catch it. These tests lock what actually happens today rather
 * than what ought to happen.</p>
 *
 * <p><b>Production code under test:</b></p>
 * <ul>
 *   <li>{@link EncabezadoBuilder#initEncabezado} — the single line that copies
 *       the setting into the entity
 *       ({@code encabezado.setProveedorSistemas(appSettings.getProvedor())}).</li>
 *   <li>{@link FacturaElectronicaStrategy#buildEncabezado} and
 *       {@link ReciboElectronicoPagoStrategy#buildEncabezado} — the two shapes:
 *       FE delegates to the shared builder, REP inlines the same call because
 *       the REP XSD has no {@code CodigoActividadEmisor}.</li>
 *   <li>{@code strategy.buildXml(comprobante)} — the real marshalling +
 *       {@code XmlEncabezadoFlattener} pipeline, so the position assertions run
 *       on the actual emitted bytes rather than a hand-rolled string.</li>
 *   <li>{@link HaciendaXsdValidator} against the bundled
 *       {@code /xsd/v4.4/FacturaElectronica_V4.4.xsd} — what the signing step
 *       would say about the result.</li>
 * </ul>
 *
 * <p><b>NO DATABASE, NO HTTP, NO Quarkus.</b> The strategies take
 * {@link EmisorService}/{@link ReceptorService} through their constructor and
 * call {@code create}/{@code createIfNotExist} only for their side effects (the
 * return values are discarded), so Mockito stubs are enough. Deliberately a
 * plain JUnit 5 class: the finding has nothing to do with persistence or the
 * REST layer, and a {@code @QuarkusTest} would drag a PostgreSQL boot into a
 * pure model-to-XML assertion.</p>
 *
 * <p><b>Characterized behaviour — read before changing anything.</b></p>
 * <ul>
 *   <li><b>Configured value:</b> the encabezado carries exactly the configured
 *       string and the emitted XML contains {@code <ProveedorSistemas>} as the
 *       second child of the root, directly after {@code <Clave>}. Correct.</li>
 *   <li><b>{@code null}:</b> JAXB omits the element entirely — the marshalled
 *       XML has NO {@code ProveedorSistemas} node at all, because
 *       {@code @XmlElement} is declared without {@code required=true} and
 *       without {@code nillable=true}. Since the v4.4 XSD declares the element
 *       without {@code minOccurs="0"} (i.e. minOccurs 1), the resulting document
 *       is SCHEMA-INVALID. Nothing in {@code initEncabezado}, in any strategy,
 *       or in the REP copy substitutes a default or refuses to build. The
 *       failure surfaces late and elsewhere: only
 *       {@code HaciendaSigner.signXml} rejects it, so
 *       {@code HaciendaServiceFacade} reports "Error al firmar XML: XSD
 *       validation failed: ... ProveedorSistemas ..." and the document never
 *       reaches Hacienda. The one unsigned consumer,
 *       {@code ComprobanteService.enviarFacturaACliente}, does not validate at
 *       all and would attach the element-less XML to the client email.</li>
 *   <li><b>Blank ("" or whitespace):</b> the element IS emitted, but empty. The
 *       v4.4 restriction is {@code xs:string} with {@code maxLength 20} and NO
 *       {@code minLength}, so an empty value satisfies the schema and the local
 *       XSD gate stays silent — only Hacienda's business validation would
 *       object. Blank is therefore the quieter of the two failures: nothing
 *       local fails. {@code initEncabezado} does not trim, so the column value
 *       is marshalled verbatim.</li>
 * </ul>
 *
 * <p><b>Recommended guard (deliberately NOT implemented here).</b> Changing the
 * emission behaviour is a product decision, so this suite only records it. Two
 * guards worth considering, in order of preference:
 * (1) fail fast at emission — refuse to build in
 * {@code EncabezadoBuilder.initEncabezado} (and the REP inlined copy) when
 * {@code getProvedor()} is null/blank, so the operator is told to fill the
 * setting in instead of discovering it later as a signing error; and
 * (2) make the setting writable — {@code ConfiguracionAplicacion.provedor} has
 * no writer anywhere in {@code src/main/java}: its only two references are
 * read-only entity-to-DTO mappers ({@code SettingsResource:365},
 * {@code SettingsPagesResource:143}), so on a fresh install the column can only
 * be filled by hand-editing SQL. Guard (1) without (2) turns a silent
 * misconfiguration into a blocking one with no way to fix it from the UI, so (2)
 * is a prerequisite, not a follow-up.</p>
 */
class EmisionProveedorSistemasTest {

    private static final String FE_NS =
            "https://cdn.comprobanteselectronicos.go.cr/xml-schemas/v4.4/facturaElectronica";
    private static final String XSD_NS = "http://www.w3.org/2001/XMLSchema";
    private static final String FE_XSD = "/xsd/v4.4/FacturaElectronica_V4.4.xsd";

    /** A realistic configured value: the provider's cedula. */
    private static final String PROVEEDOR_CONFIGURADO = "310123456789";

    private final HaciendaXsdValidator validator = new HaciendaXsdValidator();

    // ══════════════════════════════════════════════════════════════════════
    // (a) A configured Proveedor reaches the encabezado
    // ══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("(a) initEncabezado copies the configured Proveedor verbatim")
    void initEncabezadoCopiaElProvedorConfigurado() {
        Encabezado encabezado = new Encabezado();

        EncabezadoBuilder.initEncabezado(
                appSettingsConProvedor(PROVEEDOR_CONFIGURADO), encabezado, "01");

        assertEquals(PROVEEDOR_CONFIGURADO, encabezado.getProveedorSistemas(),
                "the shared builder must copy appSettings.getProvedor() with no transform");
    }

    @Test
    @DisplayName("(a) the FE strategy's encabezado carries the configured Proveedor")
    void estrategiaFePropagaElProvedorAlEncabezado() {
        Encabezado encabezado = estrategiaFe().buildEncabezado(
                appSettingsConProvedor(PROVEEDOR_CONFIGURADO), cliente());

        assertNotNull(encabezado, "an active settings profile must produce an encabezado");
        assertEquals(PROVEEDOR_CONFIGURADO, encabezado.getProveedorSistemas());
    }

    /**
     * REP does not route through {@code initEncabezado} — the REP XSD has no
     * {@code CodigoActividadEmisor}, so the strategy inlines the same
     * {@code setProveedorSistemas} call. Pinned here so the two emission
     * shapes cannot drift apart unnoticed.
     */
    @Test
    @DisplayName("(a) the REP strategy's encabezado carries the configured Proveedor too")
    void estrategiaRepPropagaElProvedorAlEncabezado() {
        DocumentoStrategy rep = new ReciboElectronicoPagoStrategy(
                mock(EmisorService.class), mock(ReceptorService.class));

        Encabezado encabezado = rep.buildEncabezado(
                appSettingsConProvedor(PROVEEDOR_CONFIGURADO), cliente());

        assertNotNull(encabezado);
        assertEquals(PROVEEDOR_CONFIGURADO, encabezado.getProveedorSistemas(),
                "the REP inlined copy must behave like the shared builder's");
    }

    // ══════════════════════════════════════════════════════════════════════
    // (c) The marshalled XML: present, and in the right position
    // ══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("(c) emitted XML has ProveedorSistemas as the second child, right after Clave")
    void xmlEmitidoExponeProveedorSistemasJustoDespuesDeClave() throws Exception {
        String xml = xmlEmitidoPorLaEstrategiaFe(PROVEEDOR_CONFIGURADO);
        List<String> hijos = nombresDeHijosDeRaiz(xml);

        int idxClave = hijos.indexOf("Clave");
        int idxProveedor = hijos.indexOf("ProveedorSistemas");
        assertTrue(idxClave >= 0, "Clave must be emitted. Root children: " + hijos);
        assertTrue(idxProveedor >= 0, "ProveedorSistemas must be emitted. Root children: " + hijos);
        assertEquals(1, idxProveedor,
                "as the second child of the root there is nothing between Clave and it. "
                        + "Root children: " + hijos);
        assertEquals(idxClave + 1, idxProveedor,
                "ProveedorSistemas must sit IMMEDIATELY after Clave. Root children: " + hijos);

        assertEquals(PROVEEDOR_CONFIGURADO, valorDe(xml, "ProveedorSistemas"));
    }

    // ══════════════════════════════════════════════════════════════════════
    // (b) null / blank — characterized, NOT fixed
    // ══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("(b) a null Proveedor makes JAXB OMIT the element entirely")
    void proveedorNuloOmiteElElementoDelXml() throws Exception {
        Encabezado encabezado = encabezadoDeLaEstrategiaFe(null);
        assertNull(encabezado.getProveedorSistemas(),
                "precondition: the entity itself carries null");

        String xml = xmlEmitido(encabezado);

        assertFalse(nombresDeHijosDeRaiz(xml).contains("ProveedorSistemas"),
                "CHARACTERIZED: @XmlElement declared without required=true and without "
                        + "nillable=true means JAXB writes nothing at all for a null value — the "
                        + "element is absent, not empty. Root children: "
                        + nombresDeHijosDeRaiz(xml));
    }

    /**
     * The consequence that makes the null case dangerous rather than cosmetic:
     * the v4.4 schema declares the element with the default minOccurs (1), so
     * the document the app produces for a never-configured shop does not
     * validate. This is the assertion the whole suite exists for.
     */
    @Test
    @DisplayName("(b) FINDING: the null-Proveedor document is rejected by the v4.4 XSD")
    void proveedorNuloProduceUnDocumentoInvalido() throws Exception {
        String xml = xmlEmitidoPorLaEstrategiaFe(null);

        HaciendaXsdValidator.ValidationResult resultado = validator.validate(xml, FE_NS);

        assertFalse(resultado.valid,
                "a document with no ProveedorSistemas cannot satisfy the v4.4 schema");
        assertTrue(resultado.errorMessage != null
                        && resultado.errorMessage.contains("ProveedorSistemas"),
                "ProveedorSistemas is the 2nd element of the root sequence and Clave is valid, so "
                        + "the first schema error must name the missing element — that is what makes "
                        + "the failure diagnosable. Actual: " + resultado.errorMessage);
    }

    /**
     * Blank is the quieter failure: the element is written but carries nothing,
     * and {@code maxLength 20} with no {@code minLength} means the local XSD
     * gate accepts it. Only Hacienda's business validation would object.
     *
     * <p>{@code initEncabezado} performs no trim, so whatever the column holds
     * is marshalled verbatim; the assertion below therefore only requires the
     * node to be blank, not byte-identical, to keep it independent of the
     * serializer's whitespace handling inside
     * {@code XmlEncabezadoFlattener}.</p>
     */
    @ParameterizedTest(name = "Proveedor = [{0}]")
    @ValueSource(strings = {"", " ", "   "})
    @DisplayName("(b) an empty Proveedor emits an EMPTY element the XSD gate does not flag")
    void proveedorEnBlancoEmiteUnElementoVacioQueElSchemaAcepta(String valor) throws Exception {
        String xml = xmlEmitidoPorLaEstrategiaFe(valor);

        List<String> hijos = nombresDeHijosDeRaiz(xml);
        assertTrue(hijos.contains("ProveedorSistemas"),
                "CHARACTERIZED: a non-null value, even an empty one, IS marshalled. "
                        + "Root children: " + hijos);
        assertEquals(hijos.indexOf("Clave") + 1, hijos.indexOf("ProveedorSistemas"),
                "the empty element keeps the right position. Root children: " + hijos);

        String emitido = valorDe(xml, "ProveedorSistemas");
        assertNotNull(emitido, "the node exists — proveedorEnBlancoEmiteUnElementoVacio "
                + "asserts presence, not the null-omission case");
        assertTrue(emitido.isBlank(),
                "CHARACTERIZED: initEncabezado does not trim, so the value is written through "
                        + "as stored; a blank setting yields a blank node. Emitted text: ["
                        + emitido + "]");

        HaciendaXsdValidator.ValidationResult resultado = validator.validate(xml, FE_NS);
        assertFalse(mencionaProveedor(resultado),
                "CHARACTERIZED: the schema's ProveedorSistemas restriction is xs:string with "
                        + "maxLength 20 and NO minLength, so an EMPTY value raises no schema error "
                        + "and the local gate stays silent — only Hacienda would reject it. "
                        + "Result: valid=" + resultado.valid
                        + " error=" + resultado.errorMessage);
    }

    // ══════════════════════════════════════════════════════════════════════
    // The constraint being characterized, asserted against the shipped XSD
    // ══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("the shipped v4.4 XSD declares ProveedorSistemas as REQUIRED, with no minLength")
    void elXsdV44DeclaraProveedorSistemasComoObligatorio() throws Exception {
        Element declaracion = declaracionDeProveedorSistemasEnElXsd();

        String minOccurs = declaracion.getAttribute("minOccurs");
        assertTrue(minOccurs.isEmpty() || "1".equals(minOccurs),
                "minOccurs defaults to 1, so the element is mandatory. Declared minOccurs: '"
                        + minOccurs + "'");
        assertEquals("", declaracion.getAttribute("maxOccurs"),
                "maxOccurs defaults to 1, so exactly one occurrence is allowed");

        Element simpleType = primerHijoEnNamespace(declaracion, XSD_NS, "simpleType");
        assertNotNull(simpleType, "the declaration must carry an inline simpleType");
        Element restriction = primerHijoEnNamespace(simpleType, XSD_NS, "restriction");
        assertNotNull(restriction, "the simpleType must carry a restriction");
        assertEquals("xs:string", restriction.getAttribute("base"),
                "the value type is a plain string, which is why emptiness is legal");

        Element maxLength = primerHijoEnNamespace(restriction, XSD_NS, "maxLength");
        assertNotNull(maxLength, "the length cap is a facet on the restriction");
        assertEquals("20", maxLength.getAttribute("value"));

        assertNull(primerHijoEnNamespace(restriction, XSD_NS, "minLength"),
                "FINDING: there is NO minLength facet, which is exactly why a blank Proveedor is "
                        + "not caught locally. The pre-sign gate in HaciendaSigner.signXml cannot "
                        + "see it either.");
    }

    // ══════════════════════════════════════════════════════════════════════
    // Helpers — emission
    // ══════════════════════════════════════════════════════════════════════

    /** The FE strategy under test, with its two persistence collaborators stubbed. */
    private static FacturaElectronicaStrategy estrategiaFe() {
        return new FacturaElectronicaStrategy(mock(EmisorService.class), mock(ReceptorService.class));
    }

    private static Encabezado encabezadoDeLaEstrategiaFe(String valorProveedor) {
        Encabezado encabezado = estrategiaFe()
                .buildEncabezado(appSettingsConProvedor(valorProveedor), cliente());
        assertNotNull(encabezado,
                "buildEncabezado returned null — the settings profile must not be disabled");
        encabezado.setClave("50626070200310010000800100001040000000001000000000");
        encabezado.setNumeroConsecutivo("00100001040000000001");
        return encabezado;
    }

    /**
     * The real emission output: {@code strategy.buildXml} runs the JAXB
     * marshaller and {@code XmlEncabezadoFlattener} exactly as
     * {@code HaciendaServiceFacade} and {@code ComprobanteService} do.
     */
    private static String xmlEmitidoPorLaEstrategiaFe(String valorProveedor) throws Exception {
        return xmlEmitido(encabezadoDeLaEstrategiaFe(valorProveedor));
    }

    private static String xmlEmitido(Encabezado encabezado) throws Exception {
        ComprobantesEmitidos comprobante = new ComprobantesEmitidos();
        comprobante.setEncabezado(encabezado);
        comprobante.setDetalles(detalleServicio());
        comprobante.setResumen(resumen());
        return estrategiaFe().buildXml(comprobante);
    }

    /** Active profile; only the Proveedor varies between scenarios. */
    private static ConfiguracionAplicacion appSettingsConProvedor(String valorProveedor) {
        ConfiguracionAplicacion s = new ConfiguracionAplicacion();
        s.setNombrePerfil("Emision ProveedorSistemas");
        s.setEstatus(Boolean.TRUE);
        s.setProvedor(valorProveedor);
        s.setCodigoActividad("154101");
        s.setNombre("Panificadora del Sur S.A.");
        s.setIdentificacion("3100100008");
        s.setTipoIdentificacion("02");
        s.setNombreNegocio("Panificadora del Sur S.A.");
        s.setProvincia("4");
        s.setCanton("03");
        s.setDistrito("06");
        s.setBarrio("01");
        s.setDireccionCompleta("DEL CRUCE DE LA PRINCIPAL, ZONA INDUSTRIAL, MODULO 1");
        s.setCodigoPais("506");
        s.setTelefono("22223333");
        s.setCorreoElectronicoTributacion("facturacion.8@proveedor-prueba.test");
        return s;
    }

    private static Clientes cliente() {
        Clientes c = new Clientes();
        c.setName("PRODUCTOS DEL VALLE S.A.");
        c.setIdType("Cedula Juridica"); // ASCII map key, avoids depending on source encoding
        c.setIdNumber("3100100013");
        c.setProvincia("1");
        c.setCanton("01");
        c.setDistrito("01");
        c.setCodigoActividadComercial("523901");
        c.setEmail("compras@proveedor-prueba.test");
        return c;
    }

    // Shapes copied from XsdModelAlignmentTest's proven factories: they only
    // have to be non-null and structurally complete. ProveedorSistemas is the
    // element under test, the rest is scaffolding.
    private static DetalleServicio detalleServicio() {
        LineaDetalle linea = new LineaDetalle();
        linea.setNumeroLinea(1);
        linea.setCodigoCabys("2349002011400");
        linea.setCantidad(new BigDecimal("1.000"));
        linea.setUnidadMedida("Unid");
        linea.setUnidadMedidaComercial("Unidad");
        linea.setDetalle("Producto de prueba");
        linea.setPrecioUnitario(new BigDecimal("100.00000"));
        linea.setMontoTotal(new BigDecimal("100.00000"));
        linea.setSubTotal(new BigDecimal("100.00000"));
        linea.setBaseImponible(new BigDecimal("100.00000"));
        linea.setImpuestoNeto(new BigDecimal("13.00000"));
        linea.setMontoTotalLinea(new BigDecimal("113.00000"));

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

    private static ResumenFactura resumen() {
        ResumenFactura r = new ResumenFactura();
        CodigoTipoMoneda moneda = new CodigoTipoMoneda();
        moneda.setCodigoMoneda("CRC");
        r.setCodigoMoneda(moneda);
        r.setTotalVenta(new BigDecimal("100.00000"));
        r.setTotalVentaNeta(new BigDecimal("100.00000"));
        r.setTotalImpuesto(new BigDecimal("13.00000"));
        r.setTotalComprobante(new BigDecimal("113.00000"));
        MedioPagoR mp = new MedioPagoR();
        mp.setTipoMedioPago("01");
        mp.setTotalMedioPago(new BigDecimal("113.00000"));
        r.setMediosPago(List.of(mp));
        return r;
    }

    // ── XML inspection ────────────────────────────────────────────────────

    private static Document parse(String xml) throws Exception {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(true);
        dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        dbf.setXIncludeAware(false);
        dbf.setExpandEntityReferences(false);
        return dbf.newDocumentBuilder()
                .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    /** Local names of the root's element children, in document order. */
    private static List<String> nombresDeHijosDeRaiz(String xml) throws Exception {
        Element raiz = parse(xml).getDocumentElement();
        NodeList hijos = raiz.getChildNodes();
        List<String> nombres = new ArrayList<>();
        for (int i = 0; i < hijos.getLength(); i++) {
            Node n = hijos.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE) {
                nombres.add(n.getLocalName() != null ? n.getLocalName() : n.getNodeName());
            }
        }
        return nombres;
    }

    /** Text of the first element with that local name, or null when absent. */
    private static String valorDe(String xml, String nombre) throws Exception {
        NodeList encontrados = parse(xml).getElementsByTagNameNS("*", nombre);
        return encontrados.getLength() == 0 ? null : encontrados.item(0).getTextContent();
    }

    // ── XSD inspection ────────────────────────────────────────────────────

    /**
     * The single {@code ProveedorSistemas} element declaration in the FE
     * schema — it occurs once, in the root's sequence, so no disambiguation by
     * parent is needed.
     */
    private static Element declaracionDeProveedorSistemasEnElXsd() throws Exception {
        try (InputStream is = EmisionProveedorSistemasTest.class.getResourceAsStream(FE_XSD)) {
            assertNotNull(is, FE_XSD + " must be on the test classpath");
            Document xsd = parse(new String(is.readAllBytes(), StandardCharsets.UTF_8));
            NodeList elementos = xsd.getElementsByTagNameNS(XSD_NS, "element");
            for (int i = 0; i < elementos.getLength(); i++) {
                Element e = (Element) elementos.item(i);
                if ("ProveedorSistemas".equals(e.getAttribute("name"))) {
                    return e;
                }
            }
        }
        throw new AssertionError("ProveedorSistemas is not declared in " + FE_XSD);
    }

    private static Element primerHijoEnNamespace(Element padre, String namespace, String nombre) {
        NodeList hijos = padre.getChildNodes();
        for (int i = 0; i < hijos.getLength(); i++) {
            Node n = hijos.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE && nombre.equals(n.getLocalName())
                    && namespace.equals(n.getNamespaceURI())) {
                return (Element) n;
            }
        }
        return null;
    }

    /**
     * True when the schema gate blamed {@code ProveedorSistemas}. A failure
     * naming only {@code Signature} is the known pre-signing gap (the
     * signature is added later) and is not attributed to this field — same
     * allow-list as {@code XsdModelAlignmentTest.isKnownXsdGap}.
     */
    private static boolean mencionaProveedor(HaciendaXsdValidator.ValidationResult resultado) {
        if (resultado.valid) {
            return false;
        }
        String msg = resultado.errorMessage == null ? "" : resultado.errorMessage;
        if (msg.contains("xcml") || msg.contains("schema.load")) {
            return false;
        }
        return msg.contains("ProveedorSistemas");
    }
}
