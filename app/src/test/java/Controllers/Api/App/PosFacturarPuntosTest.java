package Controllers.Api.App;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import Models.Articulos.ArticuloPrecio;
import Models.Articulos.Articulos;
import Models.Cabys;
import Models.Clientes;
import Models.ComprobantesEmitidos;
import Models.ConfiguracionAplicacion;
import Services.AppSettingsService;
import Services.ArticulosService;
import Services.CabysService;
import Services.ClientService;
import Services.ComprobantesEmitidosService;
import Services.DirectoryService;
import Services.LoyaltyService;
import Services.cart.CartSessionStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Ni un punto se pierde en silencio al facturar.
 *
 * <p>El canje de puntos se movió DENTRO de la transacción de
 * {@code ComprobanteService.crearComprobante}: el débito y la factura se
 * confirman juntos o fallan juntos. Antes el canje corría después del commit y
 * su fallo se tragaba con un {@code LOG.warn}, con dos finales posibles y los
 * dos malos: el cliente pagaba el descuento en caja y los puntos no se
 * debtaban, o la venta se perdía con los puntos ya debitados.</p>
 *
 * <p>El otorgamiento de puntos ganados es el camino inverso y no puede
 * abortar una venta cobrada, así que su fallo no se traga: viaja en la
 * respuesta ({@code puntosOtorgados=false} + la referencia
 * {@code FACT-<consecutivo>} para acreditarlos a mano).</p>
 *
 * <p>{@link LoyaltyService} va STUBBED con {@code @InjectMock}: es la única
 * forma de provocar el fallo determinísticamente (una base sana jamás falla al
 * debitar). El PDF NO se mockea, porque el camino de reintento necesita la
 * factura completa con su archivo.</p>
 *
 * <p><b>Fixtures:</b> artículos exentos con código de barras propio, cliente
 * con saldo y configuración de aplicación; todo con sufijo único y borrado en
 * un {@code finally} (las facturas creadas por cada prueba y sus PDF; el
 * artículo se deja porque la venta dejó inventario que lo referencia por FK y
 * el esquema se dropea en cada arranque de %test).</p>
 */
@QuarkusTest
@DisplayName("POS: canje y otorgamiento de puntos sin pérdida silenciosa")
class PosFacturarPuntosTest extends support.ContextPathIsolation {

    private static final String BASE = "/Mercurius";
    private static final String POS = BASE + "/api/app/pos";

    /** Exento de IVA: el total de la venta es el precio efectivo, exacto. */
    private static final String PRECIO = "1000";
    private static final String PUNTOS_CLIENTE = "500";
    private static final String PUNTOS_A_CANJEAR = "100";

    @Inject ArticulosService articulosService;
    @Inject CabysService cabysService;
    @Inject ClientService clientService;
    @Inject AppSettingsService appSettingsService;
    @Inject CartSessionStore cartSessionStore;
@Inject ComprobantesEmitidosService emitidosService;
@Inject DirectoryService dirService;
@Inject jakarta.persistence.EntityManager em;

    @InjectMock
    LoyaltyService loyaltyService;

    private static final java.util.concurrent.atomic.AtomicInteger SECUENCIA =
            new java.util.concurrent.atomic.AtomicInteger(0);

    /** CABYS fijo de 10 caracteres (varchar(13)): se reutiliza entre pruebas. */
    private static final String CABYS_CODIGO = "LTPOS0001";

    // ── Auth / helpers ──────────────────────────────────────────────────

    private Map<String, String> adminSession() {
        var lp = given().redirects().follow(false).when().get(BASE + "/login");
        lp.then().statusCode(200);
        Map<String, String> cookies = new HashMap<>(lp.getCookies());
        var login = given().redirects().follow(false).cookies(cookies)
                .contentType(ContentType.URLENC)
                .formParam("j_username", "admin")
                .formParam("j_password", "admin123")
                .when().post(BASE + "/j_security_check");
        login.then().statusCode(302);
        cookies.putAll(login.getCookies());
        return cookies;
    }

    private io.restassured.specification.RequestSpecification authed(Map<String, String> cookies) {
        var spec = given().redirects().follow(false).cookies(cookies);
        String token = cookies.get("csrf-token");
        if (token == null) {
            token = cookies.get("csrftoken");
        }
        if (token != null) {
            spec.header("X-CSRF-TOKEN", token);
        }
        return spec;
    }

    private static String sufijo() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    // ── Fixtures ─────────────────────────────────────────────────────────

    private void ensureAppSettings() {
        if (appSettingsService.returnCurrent() != null) {
            return;
        }
        ConfiguracionAplicacion settings = new ConfiguracionAplicacion();
        settings.setEstatus(true);
        settings.setNombre("Cajero puntos");
        settings.setNombreNegocio("Mercurius Puntos SA");
        settings.setTipoIdentificacion("02");
        settings.setIdentificacion("310123456789");
        settings.setTelefono("88888888");
        settings.setCorreoElectronicoTributacion("puntos@mercurius.local");
        settings.setProvedor("Mercurius");
        settings.setCodigoActividad("620101");
        settings.setCodigoSucursal("001");
        settings.setCodigoTerminal("00001");
        appSettingsService.create(settings);
    }

    private Articulos sembrarArticulo() {
        Cabys cabys = cabysService.find(CABYS_CODIGO);
        if (cabys == null) {
            cabys = new Cabys(CABYS_CODIGO, "Articulo de prueba de puntos",
                    "Pruebas", "0", "https://example.com/cabys", "Activo");
            cabysService.create(cabys);
        }
        String tag = String.format("%04d", SECUENCIA.getAndIncrement());
        Articulos articulo = new Articulos();
        articulo.setNombre("Articulo puntos " + tag);
        articulo.setCodigoBarra("LTP" + tag);
        articulo.setUnidadMedida("Unidad");
        articulo.setUnidadMedidaComercial("Unidad");
        articulo.setStatus(true);
        articulo.setProcessed(true);

        ArticuloPrecio precio = new ArticuloPrecio();
        precio.setArticulo(articulo);
        precio.setPrecioCostoSinIVA(new BigDecimal(PRECIO));
        precio.setPorcentajeUtilidad(BigDecimal.ZERO);
        precio.setPrecioConUtilidad(new BigDecimal(PRECIO));
        articulo.setPrecios(new ArrayList<>(List.of(precio)));
        articulo.setCodigoCabys(cabys);

        articulosService.create(articulo);
        assertThat(articulo.getCodigo()).isNotNull();
        return articulo;
    }

    private Clientes sembrarCliente() {
        Clientes cliente = new Clientes();
        cliente.setName("Cliente puntos " + sufijo());
        cliente.setEmail("puntos-" + sufijo() + "@mercurius.local");
        cliente.setIdType("Cedula Fisica");
        cliente.setIdNumber(sufijo() + sufijo());
        cliente.setStatus(true);
        cliente.setPuntosAcumulados(new BigDecimal(PUNTOS_CLIENTE));
        clientService.create(cliente);
        assertThat(cliente.getCode()).isGreaterThan(0);
        return cliente;
    }

    /** Stock actual del artículo (null si aún no tiene fila). */
    private BigDecimal stockDe(Articulos articulo) {
        List<Models.Articulos.ArticuloStock> filas = em.createQuery(
                        "SELECT a FROM ArticuloStock a WHERE a.codigoBarra = :codigo",
                        Models.Articulos.ArticuloStock.class)
                .setParameter("codigo", articulo.getCodigoBarra())
                .getResultList();
        return filas.isEmpty() ? null : filas.get(0).getStock();
    }

    /** Carrito de una venta de 1000 con el cliente y 100 puntos en juego. */
    private void prepararVenta(Map<String, String> sesion, Articulos articulo, Clientes cliente) {
        cartSessionStore.remove("admin");
        authed(sesion).contentType(ContentType.JSON)
                .body("{\"codigoBarra\":\"" + articulo.getCodigoBarra() + "\"}")
                .when().post(POS + "/scan")
                .then().statusCode(200);
        authed(sesion).contentType(ContentType.JSON)
                .body("{\"clientCode\":" + cliente.getCode() + "}")
                .when().post(POS + "/client")
                .then().statusCode(200);
    }

    private Response facturar(Map<String, String> sesion, String puntos) {
        return authed(sesion).contentType(ContentType.JSON)
                .body("{\"tipoDocumento\":\"04\","
                        + "\"pagos\":[{\"metodoPago\":\"01\",\"monto\":" + PRECIO + "}],"
                        + "\"puntosARedimir\":" + puntos + "}")
                .when().post(POS + "/facturar");
    }

    /**
     * El carrito de un canje abortado tiene que seguir exactamente como
     * estaba: una línea, el total completo y el descuento por puntos staged.
     * Es lo que hace seguro el reintento (no hay sello de idempotencia porque
     * no hubo commit, así que la venta se vuelve a ejecutar entera).
     */
    private void assertCarritoIntacto(Map<String, String> sesion) {
        Response snapshot = authed(sesion).when().get(POS + "/cart");
        snapshot.then()
                .statusCode(200)
                .body("data.items", hasSize(1));
        var json = snapshot.then().extract().jsonPath();
        assertThat(new BigDecimal(json.getString("data.totalCarrito")))
                .as("el total del carrito se conserva integro")
                .isEqualByComparingTo(PRECIO);
        assertThat(new BigDecimal(json.getString("data.descuentoPuntos")))
                .as("el descuento por puntos sigue staged para el reintento")
                .isEqualByComparingTo(PUNTOS_A_CANJEAR);
    }

    // ── 5. El canje queda reportado como descuento en el documento ──────

    @Test
    @DisplayName("la factura de una venta con puntos trae el descuento en TotalDescuentos y la neta rebajada")
    void canjeReportadoComoDescuentoEnFactura() {
        reset(loyaltyService);
        ensureAppSettings();
        Set<Long> idsPrevios = idsDeComprobantes();
        Clientes cliente = null;
        try {
            Map<String, String> sesion = adminSession();
            Articulos articulo = sembrarArticulo();
            cliente = sembrarCliente();
            prepararVenta(sesion, articulo, cliente);

            when(loyaltyService.redeemPoints(any(Clientes.class), any(BigDecimal.class)))
                    .thenReturn(new BigDecimal(PUNTOS_A_CANJEAR));

            long id = facturar(sesion, PUNTOS_A_CANJEAR).then()
                    .statusCode(200)
                    .extract().jsonPath().getLong("data.comprobanteId");

            // Venta de 1000 exenta con 100 puntos: el documento debe decir lo
            // cobrado (900 netos con 100 de descuento), no la venta íntegra.
            // Antes de este cambio la factura salía por 1000/0 mientras la caja
            // cobraba 900: Hacienda recibía una venta mayor que la cobrada.
            Models.Resumen.ResumenFactura resumen = em.createQuery(
                            "SELECT c FROM ComprobantesEmitidos c "
                                    + "LEFT JOIN FETCH c.resumen WHERE c.id = :id",
                            Models.ComprobantesEmitidos.class)
                    .setParameter("id", id)
                    .getSingleResult()
                    .getResumen();
            assertThat(resumen).as("la factura debe traer resumen").isNotNull();
            assertThat(resumen.getTotalDescuentos())
                    .as("el canje debe reportarse como descuento del documento")
                    .isEqualByComparingTo(new BigDecimal(PUNTOS_A_CANJEAR));
            assertThat(resumen.getTotalVentaNeta())
                    .as("la neta es la venta menos el descuento")
                    .isEqualByComparingTo(new BigDecimal("900"));
            assertThat(resumen.getTotalComprobante())
                    .as("el comprobante totaliza lo cobrado (exento, sin otros cargos)")
                    .isEqualByComparingTo(new BigDecimal("900"));
        } finally {
            limpiar(idsPrevios, cliente);
        }
    }

    // ── Limpieza ────────────────────────────────────────────────────────

    private Set<Long> idsDeComprobantes() {
        return emitidosService.listAll().stream()
                .map(ComprobantesEmitidos::getId)
                .collect(Collectors.toCollection(HashSet::new));
    }

    /**
     * Borra lo que la prueba dejó: las facturas nuevas y su PDF. Los
     * servicios de borrado ya registran y absorben los PersistenceException,
     * así que la limpieza nunca hace fallar una prueba que sí pasó.
     */
    private void limpiar(Set<Long> idsPrevios, Clientes cliente) {
        for (ComprobantesEmitidos comprobante : emitidosService.listAll()) {
            if (!idsPrevios.contains(comprobante.getId())) {
                emitidosService.delete(comprobante);
                try {
                    Files.deleteIfExists(Paths.get(dirService.getFacturasDirPath(),
                            "tiqueteElectronico_" + comprobante.getId() + ".pdf"));
                } catch (java.io.IOException e) {
                    // El PDF vive en disco y sobrevive al %test; si no se puede
                    // borrar, el id se reinicia en el próximo arranque y un
                    // archivo viejo podría hacer pasar un PDF_NO_GENERADO.
                    throw new AssertionError("No se pudo borrar el PDF de prueba "
                            + comprobante.getId() + ": " + e.getMessage(), e);
                }
            }
        }
        if (cliente != null) {
            clientService.delete(cliente);
        }
        cartSessionStore.remove("admin");
    }

    // ── 1. El canje aborta la venta y NO se pierde ni el carrito ni el saldo ──

    @Test
    @DisplayName("si el canje falla no hay factura, el carrito queda intacto y al reintentar se factura una sola vez")
    void canjeFallidoAbortaSinFacturaYElReintentoCobraUnaSolaVez() {
        reset(loyaltyService);
        ensureAppSettings();
        Set<Long> idsPrevios = idsDeComprobantes();
        Clientes cliente = null;
        try {
            Map<String, String> sesion = adminSession();
            Articulos articulo = sembrarArticulo();
            cliente = sembrarCliente();
            prepararVenta(sesion, articulo, cliente);

            // El subsistema de lealtad revienta el débito.
            when(loyaltyService.redeemPoints(any(Clientes.class), any(BigDecimal.class)))
                    .thenThrow(new IllegalStateException("base de lealtad no disponible"));

            facturar(sesion, PUNTOS_A_CANJEAR).then()
                    .statusCode(409)
                    .body("error.code", equalTo("PUNTOS_NO_CANJEADOS"));

            // NINGUN comprobante: el canje va antes de la primera escritura y
            // comparte la transacción con la factura.
            assertThat(idsDeComprobantes())
                    .as("un canje fallido no puede dejar una factura cobrada sin débito")
                    .isEqualTo(idsPrevios);

            // Y NINGUN movimiento de stock: el ajuste corre dentro de la misma
            // transacción revertida (antes vivía en PosResource, ya confirmado
            // antes del canje, y un 409 dejaba la rebaja sin factura).
            assertThat(stockDe(articulo))
                    .as("un canje fallido no puede dejar inventario rebajado sin venta")
                    .isNull();

            // El carrito sigue vivo, con el descuento staged: el cajero puede
            // corregir y reintentar sin perder la venta.
            assertCarritoIntacto(sesion);

            // Se recupera la lealtad y el MISMO carrito factura bien: el canje
            // se reintenta una vez y la venta no se duplica.
            when(loyaltyService.redeemPoints(any(Clientes.class), any(BigDecimal.class)))
                    .thenReturn(new BigDecimal(PUNTOS_A_CANJEAR));
            facturar(sesion, PUNTOS_A_CANJEAR).then()
                    .statusCode(200)
                    .body("data.pdfUrl", notNullValue())
                    .body("data.comprobanteId", notNullValue());

            Set<Long> idsDespues = idsDeComprobantes();
            assertThat(idsDespues).as("el reintento crea exactamente una factura")
                    .hasSize(idsPrevios.size() + 1);

            // Y el inventario se movió exactamente una vez: la rebaja del
            // intento fallido se revirtió con la transacción, no se sumó.
            assertThat(stockDe(articulo))
                    .as("reintento tras 409: una sola rebaja de inventario")
                    .isEqualByComparingTo(new BigDecimal("-1"));

            // Un redeemPoints por intento: el que falló y el que funcionó.
            verify(loyaltyService, times(2)).redeemPoints(any(Clientes.class), any(BigDecimal.class));
        } finally {
            limpiar(idsPrevios, cliente);
        }
    }

    // ── 2. Un canje que no debita también es un canje fallido ────────────

    @Test
    @DisplayName("si el canje no debita nada tampoco se emite la factura")
    void canjeSinDebitoAbortaLaVenta() {
        reset(loyaltyService);
        ensureAppSettings();
        Set<Long> idsPrevios = idsDeComprobantes();
        Clientes cliente = null;
        try {
            Map<String, String> sesion = adminSession();
            Articulos articulo = sembrarArticulo();
            cliente = sembrarCliente();
            prepararVenta(sesion, articulo, cliente);

            // redeemPoints devuelve ZERO cuando el saldo no alcanza: sin
            // excepción, pero igual sin débito. Con el descuento ya aplicado
            // en caja, dejarlo pasar es cobrar sin debitar.
            when(loyaltyService.redeemPoints(any(Clientes.class), any(BigDecimal.class)))
                    .thenReturn(BigDecimal.ZERO);

            facturar(sesion, PUNTOS_A_CANJEAR).then()
                    .statusCode(409)
                    .body("error.code", equalTo("PUNTOS_NO_CANJEADOS"));

            assertThat(idsDeComprobantes())
                    .as("un canje que devuelve cero no puede dejar una factura cobrada")
                    .isEqualTo(idsPrevios);
            assertCarritoIntacto(sesion);
        } finally {
            limpiar(idsPrevios, cliente);
        }
    }

    // ── 3. Los puntos ganados no desaparecen: se reportan ───────────────

    @Test
    @DisplayName("si falla el otorgamiento la venta se cobra pero el fallo queda visible para el operador")
    void falloAlOtorgarPuntosSeReportaEnLaRespuesta() {
        reset(loyaltyService);
        ensureAppSettings();
        Set<Long> idsPrevios = idsDeComprobantes();
        Clientes cliente = null;
        try {
            Map<String, String> sesion = adminSession();
            Articulos articulo = sembrarArticulo();
            cliente = sembrarCliente();
            prepararVenta(sesion, articulo, cliente);

            when(loyaltyService.redeemPoints(any(Clientes.class), any(BigDecimal.class)))
                    .thenReturn(new BigDecimal(PUNTOS_A_CANJEAR));
            doThrow(new IllegalStateException("fallo al acreditar los puntos"))
                    .when(loyaltyService)
                    .earnPoints(any(Clientes.class), any(BigDecimal.class), any(), any());

            Response respuesta = facturar(sesion, PUNTOS_A_CANJEAR);
            respuesta.then()
                    .statusCode(200)
                    .body("data.comprobanteId", notNullValue())
                    .body("data.puntosOtorgados", equalTo(false))
                    .body("data.puntosMensaje", containsString("FACT-"));

            // La venta NO se revierte: el dinero está cobrado y el documento
            // existe; lo que se pierde son los puntos, y ahora se dice.
            assertThat(idsDeComprobantes())
                    .as("el fallo de lealtad no puede borrar una venta ya cobrada")
                    .hasSize(idsPrevios.size() + 1);
            authed(sesion).when().get(POS + "/cart").then()
                    .body("data.items", hasSize(0));
        } finally {
            limpiar(idsPrevios, cliente);
        }
    }

    // ── 4. Control: el camino feliz no reporta nada ──────────────────────

    @Test
    @DisplayName("una venta sin incidencias de lealtad no reporta puntos pendientes")
    void ventaSanaNoReportaFalloDeLealtad() {
        reset(loyaltyService);
        ensureAppSettings();
        Set<Long> idsPrevios = idsDeComprobantes();
        Clientes cliente = null;
        try {
            Map<String, String> sesion = adminSession();
            Articulos articulo = sembrarArticulo();
            cliente = sembrarCliente();
            prepararVenta(sesion, articulo, cliente);

            when(loyaltyService.redeemPoints(any(Clientes.class), any(BigDecimal.class)))
                    .thenReturn(new BigDecimal(PUNTOS_A_CANJEAR));

            facturar(sesion, PUNTOS_A_CANJEAR).then()
                    .statusCode(200)
                    .body("data.comprobanteId", notNullValue())
                    .body("data.puntosOtorgados", equalTo(true))
                    .body("data.puntosMensaje", org.hamcrest.Matchers.nullValue());

            // El canje ocurre UNA vez por venta y con el monto exacto que el
            // POS aplicó como descuento en caja.
            verify(loyaltyService, times(1)).redeemPoints(any(Clientes.class), any(BigDecimal.class));
            verify(loyaltyService, times(1))
                    .earnPoints(any(Clientes.class), any(BigDecimal.class), any(), any());
        } finally {
            limpiar(idsPrevios, cliente);
        }
    }
}
