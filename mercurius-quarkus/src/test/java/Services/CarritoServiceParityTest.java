package Services;

import Services.ArticulosService;
import Models.Articulos.Articulos;
import Models.Articulos.Carrito.ArticuloCarrito;
import Models.Articulos.Carrito.CartOperationResult;
import Models.Articulos.Carrito.CartOperationResult.Severity;
import Models.Articulos.Carrito.CartOperationResult.Status;
import Models.Articulos.Carrito.CartSessionContext;
import Models.Articulos.Promocion;
import Models.Clients;
import Models.PagoEntry;
import Models.Users;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.contains;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.same;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Suite de paridad conductual para {@link CarritoService} refactorizado (T5 del
 * plan mercurius-jsf-to-api-migration), requerida por el todo T9.
 * <p>
 * Suite unitaria Mockito puro: sin arranque de Quarkus, sin base de datos. El
 * estado mutable que antes vivía en el bean ahora viaja en un
 * {@link CartSessionContext} nuevo por prueba, y los resultados de UI llegan
 * como {@link CartOperationResult} en vez de llamadas directas a
 * FacesContext/PrimeFaces.
 *
 * <h2>MAPEO DE SEVERIDADES LEGACY (documentación exigida por T9)</h2>
 * <p>
 * {@code CartOperationResult.Severity} es un espejo fiel de las severidades de
 * {@code jakarta.faces.application.FacesMessage}; la traducción 1:1 la hace el
 * controlador ({@code CrearTiqueteController.applyCartOperationResult}):
 *
 * <pre>
 *  CarritoService (post-T5)         | Legacy JSF (pre-T5)                    | Método productor
 *  ---------------------------------+----------------------------------------+-----------------------------------------------------------
 *  Severity.INFO                    | FacesMessage.SEVERITY_INFO             | processCodigoBarra ("Artículo agregado")
 *  Severity.WARN                    | FacesMessage.SEVERITY_WARN             | revisarCarrito ("Carrito vacío")
 *  Severity.ERROR                   | FacesMessage.SEVERITY_ERROR            | processCodigoBarra (código vacío / no encontrado / sin cantidad)
 *  severity = null + jsCommand      | PrimeFaces.current().executeScript     | revisarCarrito (diálogo pago), cancel (window.close)
 *
 * <p>
 * Los textos en español se asercionan EXACTOS a los literales de
 * {@code Services/CarritoService.java}.
 */
@ExtendWith(MockitoExtension.class)
class CarritoServiceParityTest {

    @Mock
    private ArticulosService articulosService;

    @Mock
    @Mock
    private InventarioService inventario;

    @Mock
    private StockAlertService stockAlertService;

    @InjectMocks
    private CarritoService carritoService;

    private CartSessionContext ctx;

    private Users cajero;

    @BeforeEach
    void setUp() {
        ctx = new CartSessionContext();
        cajero = new Users();
        cajero.setUsername("cajero1");
    }

    // ------------------------------------------------------------------
    // Helpers de fixtures (sin BD: precioPersonalizado evita getLastPrecio,
    // codigoCabys null implica tasa de impuesto 0 en CarritoCalculations).
    // ------------------------------------------------------------------

    private static Articulos articulo(long codigo, String nombre) {
        Articulos articulo = new Articulos();
        articulo.setCodigo(codigo);
        articulo.setNombre(nombre);
        return articulo;
    }

    private static ArticuloCarrito item(Articulos articulo, BigDecimal cantidad, BigDecimal precioPersonalizado) {
        ArticuloCarrito linea = new ArticuloCarrito();
        linea.setArticulo(articulo);
        linea.setCantidad(cantidad);
        linea.setPrecioPersonalizado(precioPersonalizado);
        return linea;
    }

    /** Comparación BigDecimal por valor (escala indiferente), con mensaje claro. */
    private static void assertDecimal(String esperado, BigDecimal real) {
        assertNotNull(real, "se esperaba un decimal pero fue null");
        assertEquals(0, real.compareTo(new BigDecimal(esperado)),
                "esperado " + esperado + " pero fue " + real.toPlainString());
    }

    // ------------------------------------------------------------------
    // addArticulo(ctx, articulo, cantidad): método VOID — la retroalimentación
    // INFO "Artículo agregado" vive en processCodigoBarra (ARTICULO_AGREGADO);
    // aquí se aserta la semántica de crecimiento/fusión del carrito.
    // ------------------------------------------------------------------

    @Test
    void addArticulo_articuloNuevo_agregaLineaAlCarrito() {
        Articulos art1 = articulo(1L, "Arroz");

        carritoService.addArticulo(ctx, art1, new BigDecimal("2"));

        assertEquals(1, ctx.getCarrito().size(), "el carrito debe crecer a 1 línea");
        ArticuloCarrito linea = ctx.getCarrito().get(0);
        assertSame(art1, linea.getArticulo(), "la línea debe referenciar el artículo agregado");
        assertDecimal("2", linea.getCantidad());
        assertFalse(linea.isPromo(), "línea nueva nunca nace como promo");
    }

    @Test
    void addArticulo_mismoArticulo_fusionaCantidad() {
        Articulos art1 = articulo(2L, "Leche");

        carritoService.addArticulo(ctx, art1, new BigDecimal("2"));
        carritoService.addArticulo(ctx, art1, new BigDecimal("3"));

        assertEquals(1, ctx.getCarrito().size(), "mismo código no-promo debe fusionarse en una línea");
        assertDecimal("5", ctx.getCarrito().get(0).getCantidad());
    }

    @Test
    void addArticulo_lineaPromoNoSeFusiona_creaLineaNueva() {
        Articulos art1 = articulo(3L, "Café");
        ArticuloCarrito promoLinea = item(art1, BigDecimal.ONE, new BigDecimal("1000"));
        promoLinea.setPromo(true);
        ctx.getCarrito().add(promoLinea);

        carritoService.addArticulo(ctx, art1, new BigDecimal("1"));

        assertEquals(2, ctx.getCarrito().size(),
                "una línea promo con el mismo código NO debe fusionarse (guard !isPromo)");
        assertDecimal("1", promoLinea.getCantidad(), "la línea promo conserva su cantidad");
    }

    private static void assertDecimal(String esperado, BigDecimal real, String mensaje) {
        assertNotNull(real, mensaje + ": se esperaba un decimal pero fue null");
        assertEquals(0, real.compareTo(new BigDecimal(esperado)),
                mensaje + ": esperado " + esperado + " pero fue " + real.toPlainString());
    }

    // ------------------------------------------------------------------
    // processCodigoBarra: cuatro salidas legadas
    // ------------------------------------------------------------------

    @Test
    void processCodigoBarra_codigoBlanco_errorSinTocarDependencias() {
        ctx.setCodigoBarra("");
        ctx.setCantidadArticulo(new BigDecimal("1"));

        CartOperationResult resultado = carritoService.processCodigoBarra(ctx);

        assertNotNull(resultado);
        assertEquals(Status.CODIGO_VACIO, resultado.status);
        assertEquals(Severity.ERROR, resultado.severity, "ERROR espeja FacesMessage.SEVERITY_ERROR");
        assertEquals("Código de barra vacío o nulo", resultado.summary);
        assertEquals("El código de barra no corresponde a un artículo válido", resultado.detail);
        assertNull(resultado.jsCommand, "un mensaje puro no ejecuta script");
        assertTrue(ctx.getCarrito().isEmpty(), "el carrito no debe tocarse");
        verifyNoInteractions(articulosService);
    }

    @Test
    void processCodigoBarra_codigoNulo_mismoErrorQueBlanco() {
        ctx.setCodigoBarra(null);

        CartOperationResult resultado = carritoService.processCodigoBarra(ctx);

        assertNotNull(resultado);
        assertEquals(Status.CODIGO_VACIO, resultado.status);
        assertEquals(Severity.ERROR, resultado.severity);
        assertEquals("Código de barra vacío o nulo", resultado.summary);
    }

    @Test
    void processCodigoBarra_articuloNoEncontrado_error() {
        ctx.setCodigoBarra("9999999999999");
        ctx.setCantidadArticulo(BigDecimal.ONE);
        when(articulosService.findByBarCode("9999999999999")).thenReturn(null);

        CartOperationResult resultado = carritoService.processCodigoBarra(ctx);

        assertNotNull(resultado);
        assertEquals(Status.ARTICULO_NO_ENCONTRADO, resultado.status);
        assertEquals(Severity.ERROR, resultado.severity);
        assertEquals("Artículo no encontrado", resultado.summary);
        assertEquals("El código de barra no corresponde a un artículo válido", resultado.detail);
        assertNull(resultado.jsCommand);
        assertTrue(ctx.getCarrito().isEmpty());
        verify(articulosService).findByBarCode("9999999999999");
            }

    @Test
    void processCodigoBarra_cantidadInvalida_errorParaCeroYNegativos() {
        Articulos art1 = articulo(4L, "Sal");
        ctx.setCodigoBarra("4001234567895");
        when(articulosService.findByBarCode("4001234567895")).thenReturn(art1);

        for (String cantidadInvalida : new String[] {"0", "-1"}) {
            ctx.setCantidadArticulo(new BigDecimal(cantidadInvalida));

            CartOperationResult resultado = carritoService.processCodigoBarra(ctx);

            assertNotNull(resultado, "cantidad " + cantidadInvalida);
            assertEquals(Status.CANTIDAD_INVALIDA, resultado.status, "cantidad " + cantidadInvalida);
            assertEquals(Severity.ERROR, resultado.severity, "cantidad " + cantidadInvalida);
            assertEquals("No hay cantidad", resultado.summary, "cantidad " + cantidadInvalida);
            assertEquals("La cantidad es inválida", resultado.detail, "cantidad " + cantidadInvalida);
        }
        assertTrue(ctx.getCarrito().isEmpty(), "ninguna cantidad inválida agrega líneas");
    }

    @Test
    void processCodigoBarra_exito_agregaReseteaCamposYAlternaResetFlag() {
        Articulos art1 = articulo(5L, "Pan");
        ctx.setCodigoBarra("4001234567895");
        ctx.setCantidadArticulo(new BigDecimal("3"));
        ctx.setResetFlag(false);
        when(articulosService.findByBarCode("4001234567895")).thenReturn(art1);

        CartOperationResult resultado = carritoService.processCodigoBarra(ctx);

        assertNotNull(resultado);
        assertEquals(Status.ARTICULO_AGREGADO, resultado.status);
        assertEquals(Severity.INFO, resultado.severity, "INFO espeja FacesMessage.SEVERITY_INFO");
        assertEquals("Artículo agregado", resultado.summary);
        assertEquals("El artículo fue agregado al carrito", resultado.detail);
        assertNull(resultado.jsCommand, "el mensaje legado no llevaba executeScript");
        assertEquals(1, ctx.getCarrito().size());
        assertDecimal("3", ctx.getCarrito().get(0).getCantidad());
        assertEquals("", ctx.getCodigoBarra(), "código de barra se limpia tras éxito");
        assertDecimal("1", ctx.getCantidadArticulo(), "cantidad vuelve a ONE");
        assertTrue(ctx.isResetFlag(), "resetFlag alterna false -> true");
            }

    @Test
    void processCodigoBarra_exitoSegundoEscaneo_fusionaCantidadEnMismaLinea() {
        Articulos art1 = articulo(6L, "Galletas");
        ctx.setCodigoBarra("4001234567895");
        ctx.setCantidadArticulo(new BigDecimal("2"));
        when(articulosService.findByBarCode("4001234567895")).thenReturn(art1);

        carritoService.processCodigoBarra(ctx);
        ctx.setCodigoBarra("4001234567895");
        ctx.setCantidadArticulo(new BigDecimal("2"));
        carritoService.processCodigoBarra(ctx);

        assertEquals(1, ctx.getCarrito().size(),
                "re-escaneo del mismo artículo fusiona (comparación == sobre Long del mismo Articulos)");
        assertDecimal("4", ctx.getCarrito().get(0).getCantidad());
    }

    // ------------------------------------------------------------------
    // revisarCarrito
    // ------------------------------------------------------------------

    @Test
    void revisarCarrito_vacio_warnSinScript() {
        CartOperationResult resultado = carritoService.revisarCarrito(ctx);

        assertNotNull(resultado);
        assertEquals(Status.CARRITO_VACIO, resultado.status);
        assertEquals(Severity.WARN, resultado.severity, "WARN espeja FacesMessage.SEVERITY_WARN");
        assertEquals("Carrito vacío", resultado.summary);
        assertEquals("Agregue artículos al carrito antes de continuar", resultado.detail);
        assertNull(resultado.jsCommand, "carrito vacío NO abre el diálogo de pago");
    }

    @Test
    void revisarCarrito_conItems_scriptDialogoPago() {
        ctx.getCarrito().add(item(articulo(7L, "Jugo"), BigDecimal.ONE, new BigDecimal("500")));

        CartOperationResult resultado = carritoService.revisarCarrito(ctx);

        assertNotNull(resultado);
        assertEquals(Status.PAGO_DIALOG_LISTO, resultado.status);
        assertEquals("PF('PagoDialog').show();", resultado.jsCommand);
        assertNull(resultado.severity, "resultado tipo script no lleva FacesMessage");
        assertNull(resultado.summary);
        assertNull(resultado.detail);
    }

    // ------------------------------------------------------------------
    // calcularVuelto: vuelto = totalPagado − (totalCarrito − descuentoPuntos),
    // redondeado a colones enteros con HALF_UP (setScale(0)) — paridad exacta.
    // ------------------------------------------------------------------

    @Test
    void calcularVuelto_sinPagosMultiplesNiPuntos() {
        ctx.getCarrito().add(item(articulo(8L, "Aceite"), new BigDecimal("2"), new BigDecimal("100")));
        ctx.setTotalPagado(new BigDecimal("500"));

        carritoService.calcularVuelto(ctx, new BigDecimal("600"));

        assertDecimal("500", ctx.getPago(), "pago toma el totalPagado");
        assertDecimal("300", ctx.getVuelto(), "vuelto = 500 − (200 − 0)");
        assertEquals(0, ctx.getVuelto().scale(), "vuelto queda en escala 0 (colones enteros)");
        assertEquals("Vuelto: 300 colones", carritoService.getVueltoString(ctx));
    }

    @Test
    void calcularVuelto_conDescuentoPuntos_restaDelNeto() {
        ctx.getCarrito().add(item(articulo(9L, "Azúcar"), new BigDecimal("2"), new BigDecimal("100")));
        ctx.setTotalPagado(new BigDecimal("250"));
        ctx.setDescuentoPuntos(new BigDecimal("50"));

        carritoService.calcularVuelto(ctx, new BigDecimal("600"));

        assertDecimal("250", ctx.getPago());
        assertDecimal("100", ctx.getVuelto(), "vuelto = 250 − (200 − 50): los puntos bajan el neto");
    }

    @Test
    void calcularVuelto_pagoEnMultiplesFormas_sumaEntradasYTruncaAColones() {
        // Dos PagoEntry (efectivo + SINPE) como en el diálogo de pago legado:
        // totalPagado es la suma de las entradas.
        PagoEntry efectivo = new PagoEntry();
        efectivo.setMetodoPago("01");
        efectivo.setMonto(new BigDecimal("300"));
        PagoEntry sinpe = new PagoEntry();
        sinpe.setMetodoPago("04");
        sinpe.setMonto(new BigDecimal("175.25"));
        ctx.setTotalPagado(efectivo.getMonto().add(sinpe.getMonto()));

        ctx.getCarrito().add(item(articulo(10L, "Fideo"), BigDecimal.ONE, new BigDecimal("200.50")));
        ctx.getCarrito().add(item(articulo(11L, "Sopa"), new BigDecimal("2"), new BigDecimal("100")));
        ctx.setDescuentoPuntos(new BigDecimal("0.50"));

        carritoService.calcularVuelto(ctx, new BigDecimal("600"));

        assertDecimal("475.25", ctx.getPago(), "totalPagado = 300 + 175.25");
        assertDecimal("75", ctx.getVuelto(),
                "75.25 crudo → setScale(0,HALF_UP)=75: QUIRK legado, el vuelto siempre es colones enteros");
    }

    @Test
    void calcularVuelto_redondeoHalfUp_haciaArriba() {
        ctx.getCarrito().add(item(articulo(12L, "Tomate"), BigDecimal.ONE, new BigDecimal("33.335")));
        ctx.setTotalPagado(new BigDecimal("100"));

        carritoService.calcularVuelto(ctx, new BigDecimal("600"));

        assertDecimal("67", ctx.getVuelto(), "66.665 → HALF_UP a escala 0 = 67");
        assertEquals("Vuelto: 67 colones", carritoService.getVueltoString(ctx));
    }

    @Test
    void calcularVuelto_pagoInsuficiente_reportaFaltante() {
        ctx.getCarrito().add(item(articulo(13L, "Sal"), new BigDecimal("2"), new BigDecimal("100")));
        ctx.setTotalPagado(new BigDecimal("150"));

        carritoService.calcularVuelto(ctx, new BigDecimal("600"));

        assertDecimal("-50", ctx.getVuelto(), "150 − 200 = faltante de 50");
        assertEquals("Faltante: 50 colones", carritoService.getVueltoString(ctx));
    }

    @Test
    void calcularVuelto_pagoExacto_vueltoCero() {
        ctx.getCarrito().add(item(articulo(14L, "Panela"), BigDecimal.ONE, new BigDecimal("100")));
        ctx.setTotalPagado(new BigDecimal("100"));

        carritoService.calcularVuelto(ctx, new BigDecimal("600"));

        assertDecimal("0", ctx.getVuelto());
        assertEquals("Vuelto: 0 colones", carritoService.getVueltoString(ctx));
    }

    // ------------------------------------------------------------------
    // removeArticulo: elimina la línea y REPROCESA promociones
    // (procesarPromocionesCarrito) — observable por contenido del carrito.
    // ------------------------------------------------------------------

    @Test
    void removeArticulo_eliminaLinea_yBarridoReprocesoPurgaCantidadCero() {
        Articulos artA = articulo(15L, "Cereal");
        Articulos artB = articulo(16L, "Maní");
        ArticuloCarrito lineaA = item(artA, new BigDecimal("5"), new BigDecimal("100"));
        // Línea huérfana con cantidad 0: SOLO el reprocesamiento de promociones
        // (Paso 3 removeIf <= 0) la saca del carrito.
        ArticuloCarrito lineaCero = item(artB, BigDecimal.ZERO, new BigDecimal("50"));
        ctx.getCarrito().add(lineaA);
        ctx.getCarrito().add(lineaCero);

        carritoService.removeArticulo(ctx, lineaA, cajero);

        assertFalse(ctx.getCarrito().contains(lineaA), "la línea removida sale del carrito");
        assertFalse(ctx.getCarrito().contains(lineaCero),
                "la línea con cantidad 0 fue purgada por el reprocesamiento de promociones");
        assertTrue(ctx.getCarrito().isEmpty());

        // Efecto lateral legado preservado: bitácora de modificación del carrito.
                        eq("Modificacion Carrito"),
                contains("cajero1"),
                same(cajero),
                eq(0),
                eq("CrearTiqueteController.removeArticulo"),
                any(),
                isNull());
            }

    @Test
    void removeArticulo_reprocesaPromocion_aplicaLineaPromocionalConDescuento() {
        Articulos art1 = articulo(17L, "Refresco");
        Promocion promo = new Promocion();
        promo.setNombre("Combo Refresco");
        promo.setDescuento(new BigDecimal("10"));
        promo.setActiva(true);
        promo.setFechaInicio(new Date(System.currentTimeMillis() - 3_600_000L));
        promo.setFechaFin(new Date(System.currentTimeMillis() + 3_600_000L));

        // Línea del carrito portadora de la promoción activa.
        ArticuloCarrito linea = item(art1, new BigDecimal("2"), new BigDecimal("100"));
        linea.setPromociones(new ArrayList<>(List.of(promo)));

        // Requerimiento espejo: Lombok @Data compara TODOS los campos, así que
        // para que promocionAplica calce, el requerimiento debe ser idéntico
        // campo por campo (incluida su lista de promociones) al chequear.
        ArticuloCarrito requerimiento = item(art1, new BigDecimal("2"), new BigDecimal("100"));
        requerimiento.setPromociones(new ArrayList<>(List.of(promo)));
        promo.setArticulosCarrito(new ArrayList<>(List.of(requerimiento)));

        ArticuloCarrito relleno = item(articulo(18L, "Chicle"), BigDecimal.ONE, new BigDecimal("10"));
        ctx.getCarrito().add(linea);
        ctx.getCarrito().add(relleno);

        carritoService.removeArticulo(ctx, relleno, cajero);

        List<ArticuloCarrito> carrito = ctx.getCarrito();
        assertEquals(2, carrito.size(), "queda la línea original + la línea promocional generada");
        ArticuloCarrito promoGenerada = carrito.stream()
                .filter(ArticuloCarrito::isPromo)
                .findFirst()
                .orElseThrow(() -> new AssertionError("el reproceso debió generar una línea promo"));
        assertDecimal("10", promoGenerada.getDescuento(), "descuento de la promoción aplicado");
        assertDecimal("2", promoGenerada.getCantidad());
        assertTrue(promoGenerada.getPromociones().contains(promo));
        assertFalse(linea.isPromo(), "la línea original permanece sin promo");
        assertDecimal("2", linea.getCantidad(), "la línea original conserva su cantidad");
    }

    // ------------------------------------------------------------------
    // cancel: bitácora + reinicio completo del contexto + window.close()
    // ------------------------------------------------------------------

    @Test
    void cancel_conItems_registraAlertaYReiniciaContextoCompleto() {
        Articulos art1 = articulo(19L, "Harina");
        ArticuloCarrito linea = item(art1, new BigDecimal("2"), new BigDecimal("100"));
        ctx.getCarrito().add(linea);
        Clients cliente = new Clients();
        cliente.setName("Juan Pérez");
        ctx.setSelectedClient(cliente);
        ctx.setCodigoBarra("99");
        ctx.setCantidadArticulo(new BigDecimal("5"));
        ctx.setResetFlag(false);
        List<ArticuloCarrito> carritoOriginal = ctx.getCarrito();

        CartOperationResult resultado = carritoService.cancel(ctx, cajero);

        // Resultado tipo script legado
        assertNotNull(resultado);
        assertEquals(Status.CANCELADO, resultado.status);
        assertEquals("window.close();", resultado.jsCommand);
        assertNull(resultado.severity, "cancel nunca mostró FacesMessage");

        // Bitácora Alertas construida y enviada
        assertEquals("Eliminacion Articulo en Carrito - Cajero: cajero1", alerta.getMensaje());
        assertEquals("facturacion", alerta.getTipo());
        assertEquals("Empty", alerta.getDespues());
        assertFalse(alerta.isVista());
        String antes = alerta.getAntes();
        assertNotNull(antes);
        assertTrue(antes.contains("Items en Carrito:"), antes);
        assertTrue(antes.contains("[Artículo: Harina"), antes);
        assertTrue(antes.contains("Cantidad: 2"), antes);
        assertTrue(antes.contains("Cliente: Juan Pérez"), antes);
        assertTrue(antes.contains("Cantidad Articulo: 5"), antes);
        assertTrue(antes.contains("Código Barra: 99"), antes);

        // Reinicio completo del contexto
        assertTrue(ctx.isResetFlag(), "resetFlag alterna false -> true");
        assertEquals("", ctx.getCodigoBarra());
        assertDecimal("1", ctx.getCantidadArticulo());
        assertNotSame(cliente, ctx.getSelectedClient(), "cliente reemplazado por instancia fresca");
        assertNotSame(carritoOriginal, ctx.getCarrito(), "lista NUEVA, no la misma referencia");
        assertTrue(ctx.getCarrito().isEmpty(), "nueva lista vacía");
    }

    @Test
    void cancel_carritoVacio_antesDiceCarritoVacioYalternaFlagInverso() {
        ctx.setResetFlag(true); // estado previo distinto: el toggle debe invertirlo

        CartOperationResult resultado = carritoService.cancel(ctx, cajero);

        assertNotNull(resultado);
        assertEquals(Status.CANCELADO, resultado.status);
        assertEquals("window.close();", resultado.jsCommand);

                String antes = captor.getValue().getAntes();
        assertTrue(antes.contains("Items en Carrito: Carrito vacío"), antes);
        assertTrue(antes.contains("Cliente: Ninguno"), "sin cliente seleccionado se registra 'Ninguno'");

        assertFalse(ctx.isResetFlag(), "resetFlag alterna true -> false");
        assertTrue(ctx.getCarrito().isEmpty());
    }

    // ------------------------------------------------------------------
    // Colateral post-venta (mismo servicio): delegación a StockAlertService
    // ------------------------------------------------------------------

    @Test
    void checkStockAlertsAfterSale_delegaEnStockAlertService() {
        carritoService.checkStockAlertsAfterSale();

        verify(stockAlertService).checkAndCreateStockAlerts();
            }
}
