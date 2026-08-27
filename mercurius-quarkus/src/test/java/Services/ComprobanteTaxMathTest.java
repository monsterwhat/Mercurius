package Services;

import Models.Articulos.Articulos;
import Models.Articulos.Carrito.ArticuloCarrito;
import Models.Cabys;
import Models.Detalles.DetalleServicio;
import Models.Detalles.Exoneracion;
import Models.Detalles.Impuesto;
import Models.Detalles.LineaDetalle;
import Models.Enums.Tipo_TarifaIVA;
import Models.ProductoExoneracion;
import Models.Resumen.ResumenFactura;
import Services.Facturas.DetalleServicioService;
import Services.Facturas.DescuentoService;
import Services.Facturas.EmisorService;
import Services.Facturas.EncabezadoService;
import Services.Facturas.ImpuestoService;
import Services.Facturas.LineaDetalleService;
import Services.Facturas.ReceptorService;
import Services.Facturas.ResumenFacturaService;
import Services.Strategies.DocumentoStrategyFactory;
import Utils.PDFGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Table-driven unit tests for the Hacienda v4.4 tax math inside
 * {@link ComprobanteService#resumenComprobante(List)} and
 * {@link ComprobanteService#detallesComprobante(List, String)}.
 *
 * <p><b>Signature parity (mirrored exactly from ComprobanteService.java):</b>
 * <ul>
 *   <li>{@code public ResumenFactura resumenComprobante(List<ArticuloCarrito> carrito)}
 *       — returns {@code null} on RuntimeException (alert swallowed).</li>
 *   <li>{@code public DetalleServicio detallesComprobante(List<ArticuloCarrito> carrito, String tipoDocumento)}
 *       — returns {@code null} on RuntimeException (alert swallowed).</li>
 *   <li>{@code Tipo_TarifaIVA.getTarifa(String)} — throws IllegalArgumentException
 *       from the switch default for unmapped rates.</li>
 * </ul>
 *
 * <p><b>Documented latent gaps asserted AS-IS (do NOT fix here):</b>
 * <ul>
 *   <li>{@code esServicio} is hardcoded {@code false} (ComprobanteService.java:370,
 *       FUTURE_ENHANCEMENTS.md §1): every amount lands in the Mercancías buckets and
 *       the TotalServ* fields stay zero. Tests assert this merchandise-only behavior.</li>
 *   <li>Tarifa "3" (and 5, 6) do not exist in Costa Rica's IVA table
 *       (FUTURE_ENHANCEMENTS.md §3): the switch default throw is a data-corruption
 *       guard, not a bug ("false alarm"). Tests pin that guard.</li>
 *   <li>An exonerated product with a non-zero CABYS rate currently counts BOTH in
 *       TotalMercExonerada AND in TotalMercanciasGravadas/TotalImpuesto — current
 *       behavior, asserted verbatim.</li>
 *   <li>TotalNoSujeto / TotalServNoSujeto / TotalMercNoSujeta have no accumulation
 *       path in resumenComprobante() and remain {@code null}.</li>
 * </ul>
 *
 * <p>Plain Mockito unit test ({@code @InjectMocks} over the 19 field-injected
 * collaborators of the @ApplicationScoped ComprobanteService). No Quarkus boot,
 * no database. CarritoCalculations runs for real so the aggregation chain is
 * exercised end-to-end.
 */
@ExtendWith(MockitoExtension.class)
class ComprobanteTaxMathTest {

    // ── The 19 field-injected collaborators (mirrored from ComprobanteService.java:80-126) ──
    @Mock private HaciendaServiceFacade haciendaServiceFacade;
    @Mock private AlertasService alertasService;
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

    // ─────────────────────────────────────────────────────────────────────────────
    // Fixture builders — minimal graphs mirroring real entity shapes
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Builds one ArticuloCarrito line. precioPersonalizado short-circuits
     * getPrecioEfectivo() away from ArticuloPrecio.getLastPrecio(), keeping the
     * fixture graph minimal while exercising the exact production read path.
     */
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

    /** ProductoExoneracion entity fixture with every copied field populated. */
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

    /** Official CR IVA table: rate string → enum (mirrors Tipo_TarifaIVA.getTarifa switch). */
    static Stream<Arguments> tarifasValidas() {
        return Stream.of(
                Arguments.of("0", Tipo_TarifaIVA.TARIFA_0_EXENTO),
                Arguments.of("0.5", Tipo_TarifaIVA.TARIFA_REDUCIDA_05),
                Arguments.of("1", Tipo_TarifaIVA.TARIFA_REDUCIDA_1),
                Arguments.of("2", Tipo_TarifaIVA.TARIFA_REDUCIDA_2),
                Arguments.of("4", Tipo_TarifaIVA.TARIFA_REDUCIDA_4),
                Arguments.of("8", Tipo_TarifaIVA.TRANSITORIO_8),
                Arguments.of("13", Tipo_TarifaIVA.TARIFA_GENERAL_13));
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Rate table {0, 0.5, 1, 2, 4, 8, 13} via Tipo_TarifaIVA.getTarifa()
    // ─────────────────────────────────────────────────────────────────────────────

    @ParameterizedTest(name = "tarifa {0} -> CodigoTarifaIVA {1}")
    @MethodSource("tarifasValidas")
    void rateTableMapsEveryValidRateToHaciendaCode(String rateStr, Tipo_TarifaIVA expected) {
        // Direct enum mapping through the switch under test
        assertThat(Tipo_TarifaIVA.getTarifa(rateStr)).isEqualTo(expected);

        // Same mapping surfaced on the built line's Impuesto (detallesComprobante).
        // BEHAVIOR PIN: the Impuesto branch is guarded by
        // getTotalImpuesto().equals(BigDecimal.ZERO) (ComprobanteService:659), so
        // zero-rate lines carry NO Impuesto entity at all (empirically verified:
        // rate "0" yields an empty impuestos list regardless of price scale).
        ArticuloCarrito articulo = item(1L, "501010101", rateStr, "100", "1");
        DetalleServicio detalles = service.detallesComprobante(List.of(articulo), "01");

        assertThat(detalles).isNotNull();
        List<LineaDetalle> lineas = detalles.getLineasDetalle();
        assertThat(lineas).hasSize(1);
        List<Impuesto> impuestos = lineas.get(0).getImpuestos();
        if ("0".equals(rateStr)) {
            assertThat(impuestos).isEmpty();
        } else {
            assertThat(impuestos).hasSize(1);
            assertThat(impuestos.get(0).getCodigoTarifaIVA()).isEqualTo(expected.getCodigo());
            assertThat(impuestos.get(0).getTarifa()).isEqualByComparingTo(rateStr);
        }
    }

    @Test
    void emptyStringRateIsTreatedAsZeroPercent() {
        // Resumen side: "" parses to impuestoPct ZERO → exempt bucket
        ResumenFactura resumen = service.resumenComprobante(
                List.of(item(11L, "501010102", "", "500", "1")));
        assertThat(resumen).isNotNull();
        assertThat(resumen.getTotalMercanciasExentas()).isEqualByComparingTo("500");
        assertThat(resumen.getTotalMercanciasGravadas()).isEqualByComparingTo("0");
        assertThat(resumen.getTotalImpuesto()).isEqualByComparingTo("0");

        // Detalles side: "" parses to a zero rate, and the equals-based guard at
        // ComprobanteService:659 means zero-tax lines carry NO Impuesto entity.
        DetalleServicio detalles = service.detallesComprobante(
                List.of(item(12L, "501010103", "", "500", "1")), "01");
        assertThat(detalles).isNotNull();
        assertThat(detalles.getLineasDetalle().get(0).getImpuestos()).isEmpty();
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // resumenComprobante bucket sums
    // ─────────────────────────────────────────────────────────────────────────────

    @Test
    void gravadoBucketSingleThirteenPercentItem() {
        ResumenFactura resumen = service.resumenComprobante(
                List.of(item(21L, "501010104", "13", "1000", "2")));

        assertThat(resumen).isNotNull();
        assertThat(resumen.getCodigoMoneda().getCodigoMoneda()).isEqualTo("CRC");
        assertThat(resumen.getTotalMercanciasGravadas()).isEqualByComparingTo("2000");
        assertThat(resumen.getTotalServGravados()).isEqualByComparingTo("0");
        assertThat(resumen.getTotalGravado()).isEqualByComparingTo("2000");
        assertThat(resumen.getTotalVenta()).isEqualByComparingTo("2000");
        assertThat(resumen.getTotalVentaNeta()).isEqualByComparingTo("2000");
        assertThat(resumen.getTotalDescuentos()).isEqualByComparingTo("0");
        assertThat(resumen.getTotalImpuesto()).isEqualByComparingTo("260"); // 2000 × 13%

        // Desglose: one entry at the general 13% tariff
        assertThat(resumen.getTotalDesgloseImpuestos()).hasSize(1);
        assertThat(resumen.getTotalDesgloseImpuestos().get(0).getCodigo()).isEqualTo("01");
        assertThat(resumen.getTotalDesgloseImpuestos().get(0).getCodigoTarifaIVA())
                .isEqualTo(Tipo_TarifaIVA.TARIFA_GENERAL_13.getCodigo());
        assertThat(resumen.getTotalDesgloseImpuestos().get(0).getTotalMontoImpuesto())
                .isEqualByComparingTo("260");
    }

    @Test
    void zeroRateItemGoesToExemptBucketAndStillEmitsDesgloseEntry() {
        ResumenFactura resumen = service.resumenComprobante(
                List.of(item(31L, "501010105", "0", "500", "1")));

        assertThat(resumen).isNotNull();
        assertThat(resumen.getTotalMercanciasExentas()).isEqualByComparingTo("500");
        assertThat(resumen.getTotalExento()).isEqualByComparingTo("500");
        assertThat(resumen.getTotalMercanciasGravadas()).isEqualByComparingTo("0");
        assertThat(resumen.getTotalGravado()).isEqualByComparingTo("0");
        assertThat(resumen.getTotalImpuesto()).isEqualByComparingTo("0");

        // Current behavior: calculateTotalTaxByRate keys zero-rate items under 0,
        // so the map is non-empty and a TARIFA_0_EXENTO desglose entry IS emitted.
        assertThat(resumen.getTotalDesgloseImpuestos()).hasSize(1);
        assertThat(resumen.getTotalDesgloseImpuestos().get(0).getCodigoTarifaIVA())
                .isEqualTo(Tipo_TarifaIVA.TARIFA_0_EXENTO.getCodigo());
    }

    @Test
    void mixedCartBucketsAreIndependent() {
        ResumenFactura resumen = service.resumenComprobante(List.of(
                item(41L, "501010106", "13", "100", "1"),
                item(42L, "501010107", "0", "40", "1")));

        assertThat(resumen).isNotNull();
        assertThat(resumen.getTotalMercanciasGravadas()).isEqualByComparingTo("100");
        assertThat(resumen.getTotalMercanciasExentas()).isEqualByComparingTo("40");
        assertThat(resumen.getTotalServGravados()).isEqualByComparingTo("0");
        assertThat(resumen.getTotalServExentos()).isEqualByComparingTo("0");
        assertThat(resumen.getTotalGravado()).isEqualByComparingTo("100");
        assertThat(resumen.getTotalExento()).isEqualByComparingTo("40");
        assertThat(resumen.getTotalExonerado()).isEqualByComparingTo("0");
        assertThat(resumen.getTotalVenta()).isEqualByComparingTo("140");
        assertThat(resumen.getTotalImpuesto()).isEqualByComparingTo("13");
        assertThat(resumen.getTotalVentaNeta()).isEqualByComparingTo("140");
    }

    @Test
    void exoneratedProductCountsInExoneradoBucketAndCurrentGravadoOverlap() {
        // CURRENT BEHAVIOR (asserted verbatim, do NOT fix): an exonerated product
        // with a non-zero CABYS rate satisfies BOTH branches — it lands in
        // TotalMercExonerada AND still adds to TotalMercanciasGravadas +
        // TotalImpuesto. Exoneration at resumen level is additive-only.
        when(productoExoneracionService.findByArticuloCodigo("61"))
                .thenReturn(exoFixture("61"));

        ResumenFactura resumen = service.resumenComprobante(
                List.of(item(61L, "501010108", "13", "200", "1")));

        assertThat(resumen).isNotNull();
        assertThat(resumen.getTotalMercExonerada()).isEqualByComparingTo("200");
        assertThat(resumen.getTotalExonerado()).isEqualByComparingTo("200");
        assertThat(resumen.getTotalMercanciasGravadas()).isEqualByComparingTo("200");
        assertThat(resumen.getTotalGravado()).isEqualByComparingTo("200");
        assertThat(resumen.getTotalImpuesto()).isEqualByComparingTo("26");
        assertThat(resumen.getTotalMercanciasExentas()).isEqualByComparingTo("0");
        verify(productoExoneracionService).findByArticuloCodigo("61");
    }

    @Test
    void esServicioHardcodedFalseRoutesEverythingToMercanciasBuckets() {
        // LATENT GAP (FUTURE_ENHANCEMENTS.md §1): esServicio is hardcoded false at
        // ComprobanteService.java:370. This test PINS the merchandise-only routing —
        // if a future fix flips the flag dynamically, this test legitimately breaks.
        when(productoExoneracionService.findByArticuloCodigo("73"))
                .thenReturn(exoFixture("73"));
        when(productoExoneracionService.findByArticuloCodigo("71")).thenReturn(null);
        when(productoExoneracionService.findByArticuloCodigo("72")).thenReturn(null);

        ResumenFactura resumen = service.resumenComprobante(List.of(
                item(71L, "501010109", "13", "100", "1"),   // taxed merchandise
                item(72L, "501010110", "0", "40", "1"),     // exempt merchandise
                item(73L, "501010111", "13", "60", "1")));  // exonerated merchandise

        assertThat(resumen).isNotNull();
        assertThat(resumen.getTotalServGravados()).isEqualByComparingTo("0");
        assertThat(resumen.getTotalServExentos()).isEqualByComparingTo("0");
        assertThat(resumen.getTotalServExonerado()).isEqualByComparingTo("0");
        assertThat(resumen.getTotalMercanciasGravadas()).isEqualByComparingTo("160"); // 100 + 60 overlap
        assertThat(resumen.getTotalMercanciasExentas()).isEqualByComparingTo("40");
        assertThat(resumen.getTotalMercExonerada()).isEqualByComparingTo("60");
    }

    @Test
    void noSujetoBucketsAreNeverPopulatedByResumenComprobante() {
        ResumenFactura resumen = service.resumenComprobante(
                List.of(item(81L, "501010112", "13", "50", "1")));

        assertThat(resumen).isNotNull();
        assertThat(resumen.getTotalNoSujeto()).isNull();
        assertThat(resumen.getTotalServNoSujeto()).isNull();
        assertThat(resumen.getTotalMercNoSujeta()).isNull();
        assertThat(resumen.getTotalVenta()).isEqualByComparingTo("50");
    }

    @Test
    void promoItemAppliesDiscountToPrecioFinalAndDescuentosBucket() {
        ArticuloCarrito promo = item(91L, "501010113", "13", "100", "2");
        promo.setPromo(true);
        promo.setDescuento(new BigDecimal("10"));

        ResumenFactura resumen = service.resumenComprobante(List.of(promo));

        assertThat(resumen).isNotNull();
        // precioFinal = 100 × (1 − 10/100) × 2 = 180
        assertThat(resumen.getTotalVenta()).isEqualByComparingTo("180");
        assertThat(resumen.getTotalMercanciasGravadas()).isEqualByComparingTo("180");
        // totalDescuentos = getTotalDescuento() × cantidad = (100 × 10%) × 2 = 20
        assertThat(resumen.getTotalDescuentos()).isEqualByComparingTo("20");
        assertThat(resumen.getTotalVentaNeta()).isEqualByComparingTo("160");
        // Tax computed on the discounted precioFinal: 180 × 13% = 23.4
        assertThat(resumen.getTotalImpuesto()).isEqualByComparingTo("23.4");
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // detallesComprobante per-line derivations
    // ─────────────────────────────────────────────────────────────────────────────

    @Test
    void subTotalMontoTotalLineaDerivationPerLineaDetalle() {
        ArticuloCarrito articulo = item(101L, "501010114", "13", "10.50", "3");

        DetalleServicio detalles = service.detallesComprobante(List.of(articulo), "01");

        assertThat(detalles).isNotNull();
        LineaDetalle linea = detalles.getLineasDetalle().get(0);
        // montoTotal = precioEfectivo × cantidad = 10.50 × 3 = 31.50
        assertThat(linea.getMontoTotal()).isEqualByComparingTo("31.50");
        // subTotal mirrors montoTotal (discounts are separate Descuento entities)
        assertThat(linea.getSubTotal()).isEqualByComparingTo("31.50");
        // montoTotalLinea also mirrors montoTotal in the current implementation
        assertThat(linea.getMontoTotalLinea()).isEqualByComparingTo("31.50");
        assertThat(linea.getBaseImponible()).isEqualByComparingTo("31.50");
        assertThat(linea.getPrecioUnitario()).isEqualByComparingTo("10.50");
        assertThat(linea.getCantidad()).isEqualByComparingTo("3");
        assertThat(linea.getNumeroLinea()).isEqualTo(0); // documented 0-based numbering
        assertThat(linea.getDetalle()).isEqualTo("Producto 501010114");
        // Unit tax 10.50 × 13% = 1.365, extended by qty 3 → 4.095
        assertThat(linea.getImpuestos().get(0).getMonto()).isEqualByComparingTo("4.095");
    }

    @Test
    void impuestoNetoEqualsMontoMinusAsumidoEmisorFabricaOnFe() {
        ArticuloCarrito articulo = item(111L, "501010115", "4", "100", "2");

        DetalleServicio detalles = service.detallesComprobante(List.of(articulo), "01");

        assertThat(detalles).isNotNull();
        LineaDetalle linea = detalles.getLineasDetalle().get(0);
        Impuesto imp = linea.getImpuestos().get(0);
        assertThat(imp.getCodigo()).isEqualTo("01");
        assertThat(imp.getCodigoTarifaIVA()).isEqualTo(Tipo_TarifaIVA.TARIFA_REDUCIDA_4.getCodigo());
        assertThat(imp.getTarifa()).isEqualByComparingTo("4");
        assertThat(imp.getMonto()).isEqualByComparingTo("8.00"); // 100 × 4% × 2
        // FE always stamps ImpuestoAsumidoEmisorFabrica = ZERO
        assertThat(linea.getImpuestoAsumidoEmisorFabrica()).isNotNull();
        assertThat(linea.getImpuestoAsumidoEmisorFabrica()).isEqualByComparingTo("0");
        // Chain: ImpuestoNeto = Monto − ImpuestoAsumidoEmisorFabrica
        assertThat(linea.getImpuestoNeto()).isEqualByComparingTo("8.00");
        assertThat(linea.getImpuestoNeto()).isEqualByComparingTo(
                imp.getMonto().subtract(linea.getImpuestoAsumidoEmisorFabrica()));
    }

    @Test
    void teSetsAsumidoZeroAndFeeOmitsNetoBaseImponibleAsumido() {
        // TE ("04"): asumido stamped ZERO, neto present, base imponible present
        DetalleServicio te = service.detallesComprobante(
                List.of(item(121L, "501010116", "13", "100", "1")), "04");
        assertThat(te).isNotNull();
        LineaDetalle teLinea = te.getLineasDetalle().get(0);
        assertThat(teLinea.getImpuestoAsumidoEmisorFabrica()).isNotNull();
        assertThat(teLinea.getImpuestoAsumidoEmisorFabrica()).isEqualByComparingTo("0");
        assertThat(teLinea.getImpuestoNeto()).isEqualByComparingTo("13.00");
        assertThat(teLinea.getBaseImponible()).isEqualByComparingTo("100");

        // FEE ("05"): XSD omits ImpuestoNeto/BaseImponible/ImpuestoAsumidoEmisorFabrica
        DetalleServicio fee = service.detallesComprobante(
                List.of(item(122L, "501010117", "13", "100", "1")), "05");
        assertThat(fee).isNotNull();
        LineaDetalle feeLinea = fee.getLineasDetalle().get(0);
        assertThat(feeLinea.getImpuestoNeto()).isNull();
        assertThat(feeLinea.getBaseImponible()).isNull();
        assertThat(feeLinea.getImpuestoAsumidoEmisorFabrica()).isNull();
        assertThat(feeLinea.getMontoTotal()).isEqualByComparingTo("100");
        assertThat(feeLinea.getSubTotal()).isEqualByComparingTo("100");
        assertThat(feeLinea.getMontoTotalLinea()).isEqualByComparingTo("100");
        assertThat(feeLinea.getImpuestos()).hasSize(1);
        assertThat(feeLinea.getImpuestos().get(0).getMonto()).isEqualByComparingTo("13.00");
    }

    @Test
    void exoneratedProductAttachesExoneracionEntityToLineImpuesto() {
        when(productoExoneracionService.findByArticuloCodigo("131"))
                .thenReturn(exoFixture("131"));

        DetalleServicio detalles = service.detallesComprobante(
                List.of(item(131L, "501010118", "13", "100", "1")), "01");

        assertThat(detalles).isNotNull();
        LineaDetalle linea = detalles.getLineasDetalle().get(0);
        assertThat(linea.getImpuestos()).hasSize(1);
        Impuesto imp = linea.getImpuestos().get(0);
        assertThat(imp.getExoneracion()).isNotNull();

        Exoneracion exo = imp.getExoneracion();
        // Field-by-field copy from the ProductoExoneracion entity fixture
        assertThat(exo.getTipoDocumentoEX1()).isEqualTo("02");
        assertThat(exo.getNumeroDocumento()).isEqualTo("123456789");
        assertThat(exo.getArticulo()).isEqualByComparingTo("7");
        assertThat(exo.getInciso()).isEqualByComparingTo("1");
        assertThat(exo.getNombreInstitucion()).isEqualTo("05");
        assertThat(exo.getNombreInstitucionOtros()).isEqualTo("Ministerio de Salud");
        assertThat(exo.getFechaEmisionEX()).isEqualTo(LocalDateTime.of(2025, 7, 14, 0, 0));
        assertThat(exo.getTarifaExonerada()).isEqualByComparingTo("13");
        assertThat(exo.getMontoExoneracion()).isEqualByComparingTo("13");
        // Bidirectional wiring between the line's Impuesto and its Exoneracion
        assertThat(exo.getImpuesto()).isSameAs(imp);
        // CURRENT BEHAVIOR: exoneration is informational at line level —
        // ImpuestoNeto still carries the FULL computed tax.
        assertThat(linea.getImpuestoNeto()).isEqualByComparingTo("13.00");
        assertThat(imp.getMonto()).isEqualByComparingTo("13.00");
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Tarifa "3" — documented false-alarm guard (FUTURE_ENHANCEMENTS.md §3)
    // ─────────────────────────────────────────────────────────────────────────────

    @Test
    void tarifaTresThrowsFromSwitchDefaultAndIsGuardedEndToEnd() {
        // 1) Direct: the switch default rejects the non-existent 3% rate
        assertThatThrownBy(() -> Tipo_TarifaIVA.getTarifa("3"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Código de impuesto no válido")
                .hasMessageContaining("3");

        // 2) End-to-end through detallesComprobante: the RuntimeException is caught,
        //    an alert is registered, and the method returns null (current contract).
        DetalleServicio detalles = service.detallesComprobante(
                List.of(item(141L, "501010119", "3", "100", "1")), "01");
        assertThat(detalles).isNull();
        verify(alertasService).registrarAlerta(
                eq("Error Detalles"),
                contains("Código de impuesto no válido: 3"),
                isNull(),
                eq(0),
                eq("detallesComprobante()"),
                isNull(),
                any());

        // 3) Resumen path survives: the inline percentage math never calls getTarifa,
        //    and the desglose loop skips unknown rates silently (empty, not null).
        ResumenFactura resumen = service.resumenComprobante(
                List.of(item(142L, "501010120", "3", "100", "1")));
        assertThat(resumen).isNotNull();
        assertThat(resumen.getTotalDesgloseImpuestos()).isNotNull();
        assertThat(resumen.getTotalDesgloseImpuestos()).isEmpty();
        assertThat(resumen.getTotalImpuesto()).isEqualByComparingTo("3");
        assertThat(resumen.getTotalMercanciasGravadas()).isEqualByComparingTo("100");
    }
}
