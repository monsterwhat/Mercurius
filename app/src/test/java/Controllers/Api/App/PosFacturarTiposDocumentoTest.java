package Controllers.Api.App;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;

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
import Services.cart.CartSessionStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Creación de cada tipo de comprobante por el POS.
 *
 * <p>Mapa de cobertura (verificado contra el código, no contra la memoria):
 * <ul>
 *   <li>TE (04): cubierto a fondo por PosResourceTest/PosFacturar*.</li>
 *   <li>FE (01): solo existía por el gemelo de formulario
 *       (PosFacturaTemplateTest); aquí se cubre el JSON.</li>
 *   <li>NC (02): el flujo soportado es DevolucionesResource
 *       (DevolucionesResourceTest); por POS se crea la fila pero SIN
 *       InformacionReferencia — Hacienda la rechazaría. Se documenta.</li>
 *   <li>ND (03), FEC (08), FEE (05), REP (10): sin flujo dedicado; solo
 *       alcanzables por el parámetro tipoDocumento del POS. Aquí se cubre
 *       ese camino con sus límites honestos.</li>
 * </ul>
 *
 * <p>Todas las pruebas usan artículo exento (tasa 0) para que el total sea el
 * precio exacto y el pago cuadre sin depender de la aritmética fiscal.
 */
@QuarkusTest
@DisplayName("POS: creación de cada tipo de comprobante")
class PosFacturarTiposDocumentoTest extends support.ContextPathIsolation {

    private static final String BASE = "/Mercurius";
    private static final String POS = BASE + "/api/app/pos";

    private static final String PRECIO = "1000";
    private static final String CABYS_CODIGO = "TDPOS0001";

    private static final java.util.concurrent.atomic.AtomicInteger SECUENCIA =
            new java.util.concurrent.atomic.AtomicInteger(0);

    @Inject ArticulosService articulosService;
    @Inject CabysService cabysService;
    @Inject ClientService clientService;
    @Inject AppSettingsService appSettingsService;
    @Inject CartSessionStore cartSessionStore;
    @Inject ComprobantesEmitidosService emitidosService;
    @Inject DirectoryService dirService;

    // ── Auth / fixtures (misma receta probada de PosResourceTest) ──

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

    private void ensureAppSettings() {
        if (appSettingsService.returnCurrent() != null) {
            return;
        }
        ConfiguracionAplicacion settings = new ConfiguracionAplicacion();
        settings.setEstatus(true);
        settings.setNombre("Cajero tipos");
        settings.setNombreNegocio("Mercurius Tipos SA");
        settings.setTipoIdentificacion("02");
        settings.setIdentificacion("310123456789");
        settings.setTelefono("88888888");
        settings.setCorreoElectronicoTributacion("tipos@mercurius.local");
        settings.setProvedor("Mercurius");
        settings.setCodigoActividad("620101");
        settings.setCodigoSucursal("001");
        settings.setCodigoTerminal("00001");
        appSettingsService.create(settings);
    }

    private Articulos sembrarArticuloExento() {
        Cabys cabys = cabysService.find(CABYS_CODIGO);
        if (cabys == null) {
            cabys = new Cabys(CABYS_CODIGO, "Articulo de prueba de tipos",
                    "Pruebas", "0", "https://example.com/cabys", "Activo");
            cabysService.create(cabys);
        }
        String tag = String.format("%04d", SECUENCIA.getAndIncrement());
        Articulos articulo = new Articulos();
        articulo.setNombre("Articulo tipos " + tag);
        articulo.setCodigoBarra("TDP" + tag);
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
        cliente.setName("Cliente tipos " + sufijo());
        cliente.setEmail("tipos-" + sufijo() + "@mercurius.local");
        cliente.setIdType("Cedula Fisica");
        cliente.setIdNumber("310243" + String.format("%04d", SECUENCIA.getAndIncrement()));
        cliente.setStatus(true);
        clientService.create(cliente);
        assertThat(cliente.getCode()).isGreaterThan(0);
        return cliente;
    }

    /** Escanea un artículo y (opcionalmente) asocia el cliente. */
    private void prepararVenta(Map<String, String> sesion, Articulos articulo, Clientes cliente) {
        cartSessionStore.remove("admin");
        authed(sesion).contentType(ContentType.JSON)
                .body("{\"codigoBarra\":\"" + articulo.getCodigoBarra() + "\"}")
                .when().post(POS + "/scan")
                .then().statusCode(200);
        if (cliente != null) {
            authed(sesion).contentType(ContentType.JSON)
                    .body("{\"clientCode\":" + cliente.getCode() + "}")
                    .when().post(POS + "/client")
                    .then().statusCode(200);
        }
    }

    private Response facturar(Map<String, String> sesion, String tipoDocumento) {
        return authed(sesion).contentType(ContentType.JSON)
                .body("{\"tipoDocumento\":\"" + tipoDocumento + "\","
                        + "\"pagos\":[{\"metodoPago\":\"01\",\"monto\":" + PRECIO + "}],"
                        + "\"puntosARedimir\":0}")
                .when().post(POS + "/facturar");
    }

    /** Fila creada por la prueba (marca de agua) con limpieza garantizada. */
    private ComprobantesEmitidos unicaFacturaNueva(Set<Long> idsPrevios) {
        List<ComprobantesEmitidos> nuevas = new ArrayList<>();
        for (ComprobantesEmitidos c : emitidosService.listAll()) {
            if (!idsPrevios.contains(c.getId())) {
                nuevas.add(c);
            }
        }
        assertThat(nuevas).hasSize(1);
        return nuevas.get(0);
    }

    private void limpiarFacturas(Set<Long> idsPrevios) {
        for (ComprobantesEmitidos comprobante : emitidosService.listAll()) {
            if (!idsPrevios.contains(comprobante.getId())) {
                emitidosService.delete(comprobante);
                try {
                    Files.deleteIfExists(Paths.get(dirService.getFacturasDirPath(),
                            "tiqueteElectronico_" + comprobante.getId() + ".pdf"));
                } catch (java.io.IOException e) {
                    throw new AssertionError("No se pudo borrar el PDF de prueba "
                            + comprobante.getId() + ": " + e.getMessage(), e);
                }
            }
        }
    }

    private Set<Long> marcaDeAgua() {
        Set<Long> ids = new HashSet<>();
        emitidosService.listAll().forEach(f -> ids.add(f.getId()));
        return ids;
    }

    // ── Pruebas ──

    @Test
    @DisplayName("FE sin cliente se rechaza (receptor obligatorio)")
    void feSinClienteSeRechaza() {
        ensureAppSettings();
        Map<String, String> sesion = adminSession();
        prepararVenta(sesion, sembrarArticuloExento(), null);

        facturar(sesion, "01")
                .then()
                .statusCode(400)
                .body("error.code", equalTo("CLIENTE_REQUERIDO"));

        // La venta queda intacta para corregir.
        authed(sesion).when().get(POS + "/cart")
                .then()
                .body("data.items", hasSize(1));
        cartSessionStore.remove("admin");
    }

    @Test
    @DisplayName("FE con cliente crea factura 01 por el JSON")
    void feConClienteCreaFactura01() {
        ensureAppSettings();
        Set<Long> idsPrevios = marcaDeAgua();
        try {
            Map<String, String> sesion = adminSession();
            prepararVenta(sesion, sembrarArticuloExento(), sembrarCliente());

            facturar(sesion, "01")
                    .then()
                    .statusCode(200)
                    .body("data.comprobanteId", notNullValue());

            ComprobantesEmitidos row = unicaFacturaNueva(idsPrevios);
            assertThat(row.getEncabezado().getCodigoDocumento()).isEqualTo("01");
        } finally {
            limpiarFacturas(idsPrevios);
        }
    }

    @Test
    @DisplayName("FEE con cliente crea factura de exportación 05")
    void feeConClienteCreaFactura05() {
        ensureAppSettings();
        Set<Long> idsPrevios = marcaDeAgua();
        try {
            Map<String, String> sesion = adminSession();
            prepararVenta(sesion, sembrarArticuloExento(), sembrarCliente());

            facturar(sesion, "05")
                    .then()
                    .statusCode(200)
                    .body("data.comprobanteId", notNullValue());

            ComprobantesEmitidos row = unicaFacturaNueva(idsPrevios);
            assertThat(row.getEncabezado().getCodigoDocumento()).isEqualTo("05");
        } finally {
            limpiarFacturas(idsPrevios);
        }
    }

    @Test
    @DisplayName("tipo desconocido cae al tiquete 04 (default documentado de la fábrica)")
    void tipoDesconocidoCaeATiquete04() {
        ensureAppSettings();
        Set<Long> idsPrevios = marcaDeAgua();
        try {
            Map<String, String> sesion = adminSession();
            prepararVenta(sesion, sembrarArticuloExento(), null);

            facturar(sesion, "99")
                    .then()
                    .statusCode(200)
                    .body("data.comprobanteId", notNullValue());

            ComprobantesEmitidos row = unicaFacturaNueva(idsPrevios);
            assertThat(row.getEncabezado().getCodigoDocumento()).isEqualTo("04");
        } finally {
            limpiarFacturas(idsPrevios);
        }
    }

    @Test
    @DisplayName("NC por POS se crea pero SIN referencia (el flujo soportado es devoluciones)")
    void ncPorPosSeCreaPeroSinReferencia() {
        ensureAppSettings();
        Set<Long> idsPrevios = marcaDeAgua();
        try {
            Map<String, String> sesion = adminSession();
            prepararVenta(sesion, sembrarArticuloExento(), sembrarCliente());

            facturar(sesion, "02")
                    .then()
                    .statusCode(200)
                    .body("data.comprobanteId", notNullValue());

            ComprobantesEmitidos row = unicaFacturaNueva(idsPrevios);
            assertThat(row.getEncabezado().getCodigoDocumento()).isEqualTo("02");
            // Hacienda exige InformacionReferencia en toda NC; el POS no la
            // pone (solo DevolucionesResource). La fila existe pero el
            // documento no pasaría la validación tributaria.
            assertThat(row.getInformacionReferencia())
                    .as("NC por POS: sin referencia el documento es rechazable")
                    .isNullOrEmpty();
        } finally {
            limpiarFacturas(idsPrevios);
        }
    }

    @Test
    @DisplayName("ND por POS se crea pero SIN referencia (no hay flujo dedicado)")
    void ndPorPosSeCreaPeroSinReferencia() {
        ensureAppSettings();
        Set<Long> idsPrevios = marcaDeAgua();
        try {
            Map<String, String> sesion = adminSession();
            prepararVenta(sesion, sembrarArticuloExento(), sembrarCliente());

            facturar(sesion, "03")
                    .then()
                    .statusCode(200)
                    .body("data.comprobanteId", notNullValue());

            ComprobantesEmitidos row = unicaFacturaNueva(idsPrevios);
            assertThat(row.getEncabezado().getCodigoDocumento()).isEqualTo("03");
            assertThat(row.getInformacionReferencia())
                    .as("ND por POS: sin referencia el documento es rechazable")
                    .isNullOrEmpty();
        } finally {
            limpiarFacturas(idsPrevios);
        }
    }

    @Test
    @DisplayName("FEC por POS se crea con cliente (08)")
    void fecPorPosSeCreaConCliente() {
        ensureAppSettings();
        Set<Long> idsPrevios = marcaDeAgua();
        try {
            Map<String, String> sesion = adminSession();
            prepararVenta(sesion, sembrarArticuloExento(), sembrarCliente());

            facturar(sesion, "08")
                    .then()
                    .statusCode(200)
                    .body("data.comprobanteId", notNullValue());

            ComprobantesEmitidos row = unicaFacturaNueva(idsPrevios);
            assertThat(row.getEncabezado().getCodigoDocumento()).isEqualTo("08");
        } finally {
            limpiarFacturas(idsPrevios);
        }
    }

    @Test
    @DisplayName("REP por POS se crea con cliente (10)")
    void repPorPosSeCreaConCliente() {
        ensureAppSettings();
        Set<Long> idsPrevios = marcaDeAgua();
        try {
            Map<String, String> sesion = adminSession();
            prepararVenta(sesion, sembrarArticuloExento(), sembrarCliente());

            facturar(sesion, "10")
                    .then()
                    .statusCode(200)
                    .body("data.comprobanteId", notNullValue());

            ComprobantesEmitidos row = unicaFacturaNueva(idsPrevios);
            assertThat(row.getEncabezado().getCodigoDocumento()).isEqualTo("10");
        } finally {
            limpiarFacturas(idsPrevios);
        }
    }
}
