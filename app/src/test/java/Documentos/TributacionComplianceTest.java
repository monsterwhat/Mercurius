package Documentos;

import Models.Articulos.Articulos;
import Models.Articulos.Carrito.ArticuloCarrito;
import Models.Cabys;
import Models.Detalles.DetalleServicio;
import Models.Detalles.Impuesto;
import Models.Detalles.LineaDetalle;
import Models.Resumen.TotalDesgloseImpuesto;
import Models.ProductoExoneracion;
import Models.Resumen.ResumenFactura;
import Services.AppSettingsService;
import Services.ComprobanteService;
import Services.ComprobantesEmitidosService;
import Services.ConsecutivoEmitidoService;
import Services.EmailService;
import Services.Facturas.DetalleServicioService;
import Services.Facturas.DescuentoService;
import Services.Facturas.EmisorService;
import Services.Facturas.EncabezadoService;
import Services.Facturas.ImpuestoService;
import Services.Facturas.LineaDetalleService;
import Services.Facturas.ReceptorService;
import Services.Facturas.ResumenFacturaService;
import Services.HaciendaCertificateService;
import Services.HaciendaServiceFacade;
import Services.HaciendaSigner;
import Services.HaciendaXsdValidator;
import Services.LoyaltyService;
import Services.ProductoExoneracionService;
import Services.Strategies.DocumentoStrategyFactory;
import Utils.PDFGenerator;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.Security;
import java.security.cert.X509Certificate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import javax.xml.crypto.dsig.XMLSignature;
import javax.xml.crypto.dsig.XMLSignatureFactory;
import javax.xml.crypto.dsig.dom.DOMValidateContext;
import javax.xml.parsers.DocumentBuilderFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tributación compliance checks: the numeric invariants Hacienda rejects
 * documents for, plus cryptographic proof that our signatures actually
 * verify (and actually break when tampered).
 *
 * <p>Complements {@code Services.ComprobanteTaxMathTest} (rate table, buckets,
 * per-line derivations) and {@code Documentos.ElectronicDocumentPipelineTest}
 * (XAdES structure across all 7 types): this class pins the <b>footings</b>
 * (totals must equal the sum of their parts), the <b>decimal scale</b> (vendor
 * XSDs enforce {@code fractionDigits <= 5} on amounts), the <b>per-type line
 * limits</b> (60/1000/400), and the <b>core cryptographic validity</b> of the
 * XAdES-EPES signature — which no other test validates.
 *
 * <p>Plain unit tests: Mockito for the service layer, real
 * {@link HaciendaSigner} with a generated keystore for the signature group.
 * No Quarkus boot, no database.
 */
@ExtendWith(MockitoExtension.class)
class TributacionComplianceTest {

    // ── Same 18 collaborators as ComprobanteTaxMathTest ──
    @Mock private HaciendaServiceFacade haciendaServiceFacade;
    @Mock private EncabezadoService encabezadoService;
    @Mock private DetalleServicioService detallesService;
    @Mock private ResumenFacturaService resumenService;
    @Mock private EmisorService emisorService;
    @Mock private ReceptorService receptorService;
    @Mock private DescuentoService descuentoService;
    @Mock private ImpuestoService impuestoService;
    @Mock private LineaDetalleService lineaService;
    @Mock private LoyaltyService loyaltyService;
    @Mock private HaciendaSigner haciendaSigner;
    @Mock private ComprobantesEmitidosService comprobantesEmitidosService;
    @Mock private EmailService emailService;
    @Mock private PDFGenerator pdfGenerator;
    @Mock private AppSettingsService appSettingsService;
    @Mock private DocumentoStrategyFactory strategyFactory;
    @Mock private ConsecutivoEmitidoService consecutivoEmitidoService;
    @Mock private ProductoExoneracionService productoExoneracionService;

    @InjectMocks
    private ComprobanteService service;

    private ArticuloCarrito item(long articuloCodigo, String cabysCodigo,
                                 String tarifaStr, String precioUnitario, String cantidad) {
        Cabys cabys = new Cabys();
        cabys.setCodigo(cabysCodigo);
        cabys.setImpuesto(tarifaStr);

        Articulos articulo = new Articulos();
        articulo.setCodigo(articuloCodigo);
        articulo.setNombre("Producto " + cabysCodigo);
        articulo.setCodigoBarra("BAR-" + cabysCodigo);
        articulo.setUnidadMedida("Unid");
        articulo.setUnidadMedidaComercial("Unid");
        articulo.setCodigoCabys(cabys);

        ArticuloCarrito item = new ArticuloCarrito();
        item.setArticulo(articulo);
        item.setCantidad(new BigDecimal(cantidad));
        item.setPrecioPersonalizado(new BigDecimal(precioUnitario));
        return item;
    }

    private ProductoExoneracion exoFixture(String articuloCodigo) {
        ProductoExoneracion exo = new ProductoExoneracion();
        exo.setArticuloCodigo(articuloCodigo);
        exo.setTipoDocumentoEX1("02");
        exo.setNumeroDocumento("123456789");
        exo.setArticulo(new BigDecimal("7"));
        exo.setInciso(new BigDecimal("1"));
        exo.setNombreInstitucion("05");
        exo.setNombreInstitucionOtros("Ministerio de Salud");
        exo.setFechaEmisionEX(LocalDateTime.of(2025, 7, 14, 0, 0));
        exo.setTarifaExonerada(new BigDecimal("13"));
        exo.setMontoExoneracion(new BigDecimal("13"));
        return exo;
    }

    private static void assertEscalaHacienda(BigDecimal valor, String campo) {
        assertThat(valor)
                .as("campo %s debe existir para poder validarse", campo)
                .isNotNull();
        assertThat(valor.scale())
                .as("campo %s = %s supera los 5 decimales que exige el XSD de Hacienda",
                        campo, valor.toPlainString())
                .isLessThanOrEqualTo(5);
    }

    // ─────────────────────────────────────────────────────────────────────
    // Footings: los totales cuadran con la suma de sus partes
    // (causa #1 de rechazo en Hacienda)
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("venta neta = venta − descuentos con promo en el carrito")
    void ventaNetaEsVentaMenosDescuentos() {
        ArticuloCarrito promo = item(201L, "502010201", "13", "100", "2");
        promo.setPromo(true);
        promo.setDescuento(new BigDecimal("10"));

        ResumenFactura resumen = service.resumenComprobante(List.of(promo));

        assertThat(resumen).isNotNull();
        assertThat(resumen.getTotalVentaNeta()).isEqualByComparingTo(
                resumen.getTotalVenta().subtract(resumen.getTotalDescuentos()));
        // Ancla numérica: 100 × 0.9 × 2 = 180 venta, 20 descuento, 160 neta
        assertThat(resumen.getTotalVenta()).isEqualByComparingTo("180");
        assertThat(resumen.getTotalDescuentos()).isEqualByComparingTo("20");
        assertThat(resumen.getTotalVentaNeta()).isEqualByComparingTo("160");
    }

    @Test
    @DisplayName("gravado + exento particionan la venta (carrito sin exonerados)")
    void bucketsParticionanVenta() {
        ResumenFactura resumen = service.resumenComprobante(List.of(
                item(202L, "502010202", "13", "1000", "1"),
                item(203L, "502010203", "0", "500", "1")));

        assertThat(resumen).isNotNull();
        assertThat(resumen.getTotalGravado().add(resumen.getTotalExento()))
                .as("gravado + exento debe cubrir toda la venta")
                .isEqualByComparingTo(resumen.getTotalVenta());
        assertThat(resumen.getTotalVenta()).isEqualByComparingTo("1500");
        assertThat(resumen.getTotalImpuesto()).isEqualByComparingTo("130");
    }

    @Test
    @DisplayName("el desglose por tarifa suma exactamente el total de impuesto")
    void desgloseSumaTotalImpuesto() {
        ResumenFactura resumen = service.resumenComprobante(List.of(
                item(204L, "502010204", "13", "1000", "1"),
                item(205L, "502010205", "4", "1000", "1"),
                item(206L, "502010206", "1", "1000", "1")));

        assertThat(resumen).isNotNull();
        BigDecimal sumaDesglose = BigDecimal.ZERO;
        for (TotalDesgloseImpuesto d : resumen.getTotalDesgloseImpuestos()) {
            assertEscalaHacienda(d.getTotalMontoImpuesto(), "desglose[" + d.getCodigoTarifaIVA() + "]");
            sumaDesglose = sumaDesglose.add(d.getTotalMontoImpuesto());
        }
        assertThat(sumaDesglose)
                .as("la suma del desglose debe cuadrar con TotalImpuesto")
                .isEqualByComparingTo(resumen.getTotalImpuesto());
        // 130 + 40 + 10
        assertThat(resumen.getTotalImpuesto()).isEqualByComparingTo("180");
    }

    @Test
    @DisplayName("exonerado documentado: la base suma en gravado y en exonerada")
    void exoneradoDobleConteoDocumentado() {
        when(productoExoneracionService.findByArticuloCodigo("207"))
                .thenReturn(exoFixture("207"));

        ResumenFactura resumen = service.resumenComprobante(
                List.of(item(207L, "502010207", "13", "100", "1")));

        assertThat(resumen).isNotNull();
        // Comportamiento vigente: la base exonerada sigue en el bucket
        // gravado (y su impuesto en TotalImpuesto) y ADEMÁS en exonerada.
        assertThat(resumen.getTotalMercanciasGravadas()).isEqualByComparingTo("100");
        assertThat(resumen.getTotalMercExonerada()).isEqualByComparingTo("100");
        assertThat(resumen.getTotalImpuesto()).isEqualByComparingTo("13");
    }

    @Test
    @DisplayName("el detalle cruza con el resumen: líneas suman venta e impuesto")
    void detalleCruzaConResumen() {
        List<ArticuloCarrito> carrito = List.of(
                item(208L, "502010208", "13", "10.50", "3"),
                item(209L, "502010209", "4", "100", "2"),
                item(210L, "502010210", "0", "25", "4"));

        DetalleServicio detalles = service.detallesComprobante(carrito, "01");
        ResumenFactura resumen = service.resumenComprobante(carrito);

        assertThat(detalles).isNotNull();
        assertThat(resumen).isNotNull();

        BigDecimal sumaLineas = BigDecimal.ZERO;
        BigDecimal sumaImpuestos = BigDecimal.ZERO;
        for (LineaDetalle linea : detalles.getLineasDetalle()) {
            sumaLineas = sumaLineas.add(linea.getMontoTotalLinea());
            for (Impuesto imp : linea.getImpuestos()) {
                sumaImpuestos = sumaImpuestos.add(imp.getMonto());
            }
        }
        assertThat(sumaLineas)
                .as("Σ MontoTotalLinea debe cuadrar con TotalVenta")
                .isEqualByComparingTo(resumen.getTotalVenta());
        assertThat(sumaImpuestos)
                .as("Σ impuesto por línea debe cuadrar con TotalImpuesto")
                .isEqualByComparingTo(resumen.getTotalImpuesto());
    }

    // ─────────────────────────────────────────────────────────────────────
    // Escala decimal: el XSD no admite más de 5 fraccionarios en montos
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("tasas fraccionarias y cantidades decimales no rompen los 5 decimales")
    void escalasAcotadasAHacienda() {
        // 10.55 × 0.5% = 0.05275 por unidad, × 1.5 = 0.079125 (6 decimales
        // sin normalizar): cantidad fraccionaria × tasa reducida es el caso
        // que estira la escala del BigDecimal.
        List<ArticuloCarrito> carrito = List.of(
                item(211L, "502010211", "0.5", "10.55", "1.5"),
                item(212L, "502010212", "13", "10.50", "3"));

        DetalleServicio detalles = service.detallesComprobante(carrito, "01");
        ResumenFactura resumen = service.resumenComprobante(carrito);

        assertThat(detalles).isNotNull();
        assertThat(resumen).isNotNull();
        for (LineaDetalle linea : detalles.getLineasDetalle()) {
            assertEscalaHacienda(linea.getMontoTotal(), "MontoTotal línea " + linea.getNumeroLinea());
            assertEscalaHacienda(linea.getMontoTotalLinea(), "MontoTotalLinea " + linea.getNumeroLinea());
            assertEscalaHacienda(linea.getBaseImponible(), "BaseImponible " + linea.getNumeroLinea());
            assertEscalaHacienda(linea.getImpuestoNeto(), "ImpuestoNeto " + linea.getNumeroLinea());
            for (Impuesto imp : linea.getImpuestos()) {
                assertEscalaHacienda(imp.getMonto(), "Impuesto.Monto línea " + linea.getNumeroLinea());
            }
        }
        assertEscalaHacienda(resumen.getTotalVenta(), "TotalVenta");
        assertEscalaHacienda(resumen.getTotalVentaNeta(), "TotalVentaNeta");
        assertEscalaHacienda(resumen.getTotalImpuesto(), "TotalImpuesto");
        assertEscalaHacienda(resumen.getTotalGravado(), "TotalGravado");
    }

    @Test
    @DisplayName("el descuento porcentual con promo no estira la escala de la venta")
    void promoNoEstiraEscalaVenta() {
        ArticuloCarrito promo = item(213L, "502010213", "13", "99.99", "3");
        promo.setPromo(true);
        promo.setDescuento(new BigDecimal("7.5"));

        ResumenFactura resumen = service.resumenComprobante(List.of(promo));

        assertThat(resumen).isNotNull();
        assertEscalaHacienda(resumen.getTotalVenta(), "TotalVenta con promo");
        assertEscalaHacienda(resumen.getTotalDescuentos(), "TotalDescuentos con promo");
        assertEscalaHacienda(resumen.getTotalVentaNeta(), "TotalVentaNeta con promo");
        assertEscalaHacienda(resumen.getTotalImpuesto(), "TotalImpuesto con promo");
    }

    // ─────────────────────────────────────────────────────────────────────
    // Matriz por tipo: límites de líneas y forma de la línea
    // ─────────────────────────────────────────────────────────────────────

    @ParameterizedTest(name = "tipo {0}: la línea lleva CABYS, cantidad y precio")
    @ValueSource(strings = {"01", "02", "03", "04", "05", "08"})
    void tiposNoRepConFormaCompleta(String tipoDocumento) {
        DetalleServicio detalles = service.detallesComprobante(
                List.of(item(214L, "502010214", "13", "100", "1")), tipoDocumento);

        assertThat(detalles).isNotNull();
        assertThat(detalles.getLineasDetalle()).hasSize(1);
        LineaDetalle linea = detalles.getLineasDetalle().get(0);
        assertThat(linea.getCodigoCabys()).isEqualTo("502010214");
        assertThat(linea.getCantidad()).isEqualByComparingTo("1");
        assertThat(linea.getPrecioUnitario()).isEqualByComparingTo("100");
    }

    @Test
    @DisplayName("REP (10): línea simplificada sin CABYS, cantidad ni precio")
    void repLineaSimplificada() {
        DetalleServicio detalles = service.detallesComprobante(
                List.of(item(215L, "502010215", "13", "100", "1")), "10");

        assertThat(detalles).isNotNull();
        assertThat(detalles.getLineasDetalle()).hasSize(1);
        LineaDetalle linea = detalles.getLineasDetalle().get(0);
        assertThat(linea.getCodigoCabys()).isNull();
        assertThat(linea.getCantidad()).isNull();
        assertThat(linea.getPrecioUnitario()).isNull();
        assertThat(linea.getMontoTotalLinea()).isNotNull();
    }

    @Test
    @DisplayName("FE/FEE aceptan 60 líneas pero la 61 se rechaza")
    void limiteSesentaLineasFeYFee() {
        List<ArticuloCarrito> sesentaYUna = new ArrayList<>();
        for (int i = 0; i < 61; i++) {
            sesentaYUna.add(item(300L + i, "502010" + (300 + i), "13", "10", "1"));
        }
        // detallesComprobante convierte el IllegalArgumentException en null
        // (contrato vigente: alerta + null, ver ComprobanteTaxMathTest).
        assertThat(service.detallesComprobante(sesentaYUna, "01")).isNull();
        assertThat(service.detallesComprobante(sesentaYUna, "05")).isNull();
    }

    @Test
    @DisplayName("TE acepta más de 60 líneas (límite 1000)")
    void teAceptaMasDeSesentaLineas() {
        List<ArticuloCarrito> sesentaYUna = new ArrayList<>();
        for (int i = 0; i < 61; i++) {
            sesentaYUna.add(item(400L + i, "502020" + (400 + i), "13", "10", "1"));
        }
        DetalleServicio detalles = service.detallesComprobante(sesentaYUna, "04");
        assertThat(detalles).isNotNull();
        assertThat(detalles.getLineasDetalle()).hasSize(61);
    }

    @Test
    @DisplayName("NC/ND/FEC aceptan 400 líneas pero la 401 se rechaza")
    void limiteCuatrocientasLineasNcNdFec() {
        List<ArticuloCarrito> cuatrocientasUna = new ArrayList<>();
        for (int i = 0; i < 401; i++) {
            cuatrocientasUna.add(item(500L + i, "502030" + (500 + i), "13", "10", "1"));
        }
        assertThat(service.detallesComprobante(cuatrocientasUna, "02")).isNull();
        assertThat(service.detallesComprobante(cuatrocientasUna, "03")).isNull();
        assertThat(service.detallesComprobante(cuatrocientasUna, "08")).isNull();
    }

    // ─────────────────────────────────────────────────────────────────────
    // Firma: validez criptográfica real, no solo estructura XAdES
    // ─────────────────────────────────────────────────────────────────────

    private static HaciendaSigner signer;
    private static X509Certificate cert;
    private static String validXml;

    @BeforeAll
    static void setUpFirma() throws Exception {
        System.setProperty("javax.xml.transform.TransformerFactory", "TestSupport.TestTransformerFactory");
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }

        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        KeyPair kp = kpg.generateKeyPair();
        JcaX509v3CertificateBuilder certBuilder = new JcaX509v3CertificateBuilder(
                new X500Name("CN=Test CA"), java.math.BigInteger.valueOf(System.currentTimeMillis()),
                new Date(System.currentTimeMillis() - 86400000L), new Date(System.currentTimeMillis() + 86400000L * 365),
                new X500Name("CN=Test"), kp.getPublic());
        ContentSigner cs = new JcaContentSignerBuilder("SHA256withRSA").build(kp.getPrivate());
        cert = new JcaX509CertificateConverter().getCertificate(certBuilder.build(cs));
        KeyStore ks = KeyStore.getInstance("PKCS12");
        ks.load(null, null);
        ks.setKeyEntry("test", kp.getPrivate(), "testpass".toCharArray(), new X509Certificate[]{cert});
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        ks.store(bos, "testpass".toCharArray());
        HaciendaCertificateService mockCert = mock(HaciendaCertificateService.class);
        KeyStore loaded = KeyStore.getInstance("PKCS12");
        loaded.load(new ByteArrayInputStream(bos.toByteArray()), "testpass".toCharArray());
        when(mockCert.loadKeyStore()).thenReturn(loaded);
        when(mockCert.getDecryptedCertificadoPassword()).thenReturn("testpass");
        signer = new HaciendaSigner(mockCert);
        HaciendaXsdValidator realValidator = new HaciendaXsdValidator();
        var f = HaciendaSigner.class.getDeclaredField("xsdValidator");
        f.setAccessible(true);
        f.set(signer, realValidator);

        validXml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <FacturaElectronica xmlns="https://cdn.comprobanteselectronicos.go.cr/xml-schemas/v4.4/facturaElectronica">
                <Clave>50626070200310010000800100001040000000001000000000</Clave>
                <ProveedorSistemas>3100100008</ProveedorSistemas>
                <CodigoActividadEmisor>154101</CodigoActividadEmisor>
                <NumeroConsecutivo>00100001040000000001</NumeroConsecutivo>
                <FechaEmision>2026-07-02T12:00:00</FechaEmision>
                <Emisor><Nombre>Panificadora del Sur S.A.</Nombre><Identificacion><Tipo>02</Tipo><Numero>3100100008</Numero></Identificacion>
                    <Ubicacion><Provincia>4</Provincia><Canton>03</Canton><Distrito>06</Distrito><OtrasSenas>ZONA INDUSTRIAL, MODULO 1</OtrasSenas></Ubicacion>
                    <CorreoElectronico>facturacion.8@proveedor-prueba.test</CorreoElectronico></Emisor>
                <Receptor><Nombre>PRODUCTOS DEL VALLE S.A.</Nombre><Identificacion><Tipo>02</Tipo><Numero>3100100013</Numero></Identificacion></Receptor>
                <CondicionVenta>01</CondicionVenta>
                <DetalleServicio><LineaDetalle><NumeroLinea>1</NumeroLinea><CodigoCABYS>2349002011400</CodigoCABYS>
                    <Cantidad>1.000</Cantidad><UnidadMedida>Unid</UnidadMedida><Detalle>Takis Fuego ES 1p 56g FLOW BAR</Detalle>
                    <PrecioUnitario>100.00000</PrecioUnitario><MontoTotal>100.00000</MontoTotal><SubTotal>100.00000</SubTotal>
                    <BaseImponible>100.00000</BaseImponible><Impuesto><Codigo>01</Codigo><CodigoTarifaIVA>08</CodigoTarifaIVA><Tarifa>13.00</Tarifa><Monto>13.00000</Monto></Impuesto>
                    <ImpuestoAsumidoEmisorFabrica>0.00000</ImpuestoAsumidoEmisorFabrica><ImpuestoNeto>13.00000</ImpuestoNeto><MontoTotalLinea>113.00000</MontoTotalLinea></LineaDetalle></DetalleServicio>
                <ResumenFactura><CodigoTipoMoneda><CodigoMoneda>CRC</CodigoMoneda><TipoCambio>1</TipoCambio></CodigoTipoMoneda>
                    <TotalVenta>100.00000</TotalVenta><TotalVentaNeta>100.00000</TotalVentaNeta><TotalImpuesto>13.00000</TotalImpuesto><TotalComprobante>113.00000</TotalComprobante></ResumenFactura>
            </FacturaElectronica>""";
    }

    /** Valida criptográficamente el núcleo XMLDSig con la llave pública dada. */
    private static boolean nucleoValido(String xmlFirmado, java.security.PublicKey llave) throws Exception {
        Document doc = parseConIds(xmlFirmado);
        NodeList firmas = doc.getElementsByTagNameNS(XMLSignature.XMLNS, "Signature");
        assertThat(firmas.getLength()).isGreaterThan(0);
        Node nodoFirma = firmas.item(0);
        DOMValidateContext contexto = new DOMValidateContext(llave, nodoFirma);
        XMLSignatureFactory fabrica = XMLSignatureFactory.getInstance("DOM");
        XMLSignature firma = fabrica.unmarshalXMLSignature(contexto);
        return firma.validate(contexto);
    }

    private static Document parseConIds(String xml) throws Exception {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(true);
        Document doc = dbf.newDocumentBuilder().parse(
                new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        // Sin esto el resolvedor JDK no dereferencia #SignedProperties:
        // el atributo Id de xades4j no es un ID DOM hasta registrarse.
        NodeList todos = doc.getElementsByTagName("*");
        for (int i = 0; i < todos.getLength(); i++) {
            Node n = todos.item(i);
            if (n instanceof org.w3c.dom.Element el && el.hasAttribute("Id")) {
                el.setIdAttribute("Id", true);
            }
        }
        return doc;
    }

    @Test
    @DisplayName("el XML firmado verifica criptográficamente con el certificado firmante")
    void firmaValidaCriptograficamente() throws Exception {
        var r = signer.signXml(validXml);
        assertTrue(r.success, "El XML válido debe firmarse: " + r.errorMessage);

        assertTrue(nucleoValido(r.signedXml, cert.getPublicKey()),
                "El núcleo de la firma debe validar con la llave del firmante");
    }

    @Test
    @DisplayName("alterar un monto del XML firmado invalida la firma")
    void documentoAlteradoInvalidaFirma() throws Exception {
        var r = signer.signXml(validXml);
        assertTrue(r.success, "El XML válido debe firmarse: " + r.errorMessage);

        // Atacante (o corrupción) cambia el total sin re-firmar.
        String alterado = r.signedXml.replace(
                "<TotalComprobante>113.00000</TotalComprobante>",
                "<TotalComprobante>114.00000</TotalComprobante>");
        assertThat(alterado).isNotEqualTo(r.signedXml);

        assertFalse(nucleoValido(alterado, cert.getPublicKey()),
                "Un documento alterado tras la firma NO debe validar");
    }

    @Test
    @DisplayName("validar con otro certificado falla aunque el XML esté intacto")
    void certificadoEquivocadoNoValida() throws Exception {
        var r = signer.signXml(validXml);
        assertTrue(r.success, "El XML válido debe firmarse: " + r.errorMessage);

        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        KeyPair otro = kpg.generateKeyPair();

        assertFalse(nucleoValido(r.signedXml, otro.getPublic()),
                "La firma no debe validar con una llave que no firmó");
    }

    @Test
    @DisplayName("la referencia es envolvente de documento completo (URI vacía)")
    void referenciaEnvolventeCubreDocumento() throws Exception {
        var r = signer.signXml(validXml);
        assertTrue(r.success, "El XML válido debe firmarse: " + r.errorMessage);

        Document doc = parseConIds(r.signedXml);
        Node nodoFirma = doc.getElementsByTagNameNS(XMLSignature.XMLNS, "Signature").item(0);
        DOMValidateContext contexto = new DOMValidateContext(cert.getPublicKey(), nodoFirma);
        XMLSignature firma = XMLSignatureFactory.getInstance("DOM").unmarshalXMLSignature(contexto);

        assertThat(firma.getSignedInfo().getReferences()).isNotEmpty();
        boolean cubreTodo = firma.getSignedInfo().getReferences().stream()
                .anyMatch(ref -> "".equals(ref.getURI()));
        assertTrue(cubreTodo,
                "Debe existir una referencia con URI vacía: la firma cubre el documento entero, "
                        + "no un fragmento que deje montos fuera del digest");
    }

    @Test
    @DisplayName("firmar dos veces el mismo XML produce firmas verificables independientes")
    void dobleFirmaProduceFirmasVerificables() throws Exception {
        var primera = signer.signXml(validXml);
        var segunda = signer.signXml(validXml);
        assertTrue(primera.success && segunda.success);

        assertTrue(nucleoValido(primera.signedXml, cert.getPublicKey()));
        assertTrue(nucleoValido(segunda.signedXml, cert.getPublicKey()));
    }
}
