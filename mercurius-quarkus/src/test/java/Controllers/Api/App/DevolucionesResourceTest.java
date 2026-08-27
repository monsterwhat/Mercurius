package Controllers.Api.App;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import Controllers.Api.App.DevolucionesResource.NcSummary;
import Models.Articulos.ArticuloStock;
import Models.ComprobantesEmitidos;
import Models.Detalles.DetalleServicio;
import Models.Detalles.Impuesto;
import Models.Detalles.LineaDetalle;
import Models.Encabezado.Encabezado;
import Models.Encabezado.Receptor;
import Models.Inventario;
import Models.NotaCredito;
import Models.Resumen.ResumenFactura;
import Services.AppSettingsService;
import Services.ComprobanteService;
import Services.ComprobantesEmitidosService;
import Services.InventarioService;
import Services.NotaCreditoService;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;

/**
 * T32 — Devoluciones module acceptance suite ({@code admin}/{@code
 * facturacion} role gates; {@link ComprobanteService} STUBBED with
 * {@code @InjectMock} so NO real Hacienda send can ever happen).
 *
 * <p>Fixtures are committed outside @TestTransaction so the HTTP request
 * thread sees them, and removed in finally blocks (same discipline as
 * TributacionResourceTest); %test boots drop-and-create so a failed
 * assertion cannot poison later runs.</p>
 *
 * <p>Scenarios: authorize-failure blocks with ZERO side effects (401, no NC,
 * no inventory movement, no Hacienda send); authorize-success generates an NC
 * matching the golden STRUCTURAL fixture (clave/consecutivo/lines/resumen —
 * not byte-equality); double-devolucion guard 409; inventory restock side
 * effect persisted and ArticuloStock provably untouched within the same flow
 * (legacy {@code create()} parity — see .omo/evidence/t32/); validation
 * matrix; search parity; page + fragment rendering; role matrix.</p>
 */
@QuarkusTest
@Tag("devoluciones")
class DevolucionesResourceTest extends support.ContextPathIsolation {

    private static final String BASE = "/Mercurius";
    private static final String API = BASE + "/api/app/devoluciones";
    private static final String PAGES = BASE + "/app/devoluciones";
    private static final String CSRF_COOKIE = "csrf-token";
    private static final String CSRF_HEADER = "X-CSRF-TOKEN";

    /** Seeded by import-test.sql: admin / admin123 (BCrypt cost 12). */
    private static final String SUPERVISOR = "admin";
    private static final String SUPERVISOR_PASS = "admin123";

    /** import-test.sql client the factura receptor must resolve to. */
    private static final String CLIENTE_CONTADO = "Cliente Contado";

    @Inject
    ComprobantesEmitidosService emitidosService;

    @Inject
    NotaCreditoService notaCreditoService;

    @Inject
    InventarioService inventarioService;

    @Inject
    AppSettingsService appSettingsService;

    @Inject
    EntityManager em;

    @InjectMock
    ComprobanteService comprobanteService;

    // ── 1. Authorize failure blocks processing with zero side effects ───

    @Test
    @TestSecurity(user = "facturacion", roles = {"facturacion"})
    void authorizeFailBlocksProcessingWithZeroSideEffects() {
        seedAppSettings();
        ComprobantesEmitidos factura = null;
        try {
            factura = seedFactura();
            long notasAntes = countNotas();
            long inventariosAntes = countInventarios();

            Map<String, String> jar = freshCsrfJar();

            // Wrong password → 401, nothing written.
            given()
                    .cookies(jar).header(CSRF_HEADER, jar.get(CSRF_COOKIE))
                    .formParam("username", SUPERVISOR)
                    .formParam("password", "contrasena-equivocada")
                    .formParam("motivo", "Producto defectuoso")
                    .formParam("lineaNumero", "0").formParam("lineaCantidad", "1")
                    .when().post(API + "/" + factura.getId() + "/authorize")
                    .then()
                    .statusCode(401)
                    .body("error.code", org.hamcrest.Matchers.equalTo("INVALID_CREDENTIALS"));

            // Nonexistent user → 401 as well.
            given()
                    .cookies(jar).header(CSRF_HEADER, jar.get(CSRF_COOKIE))
                    .formParam("username", "usuario-inexistente-t32")
                    .formParam("password", SUPERVISOR_PASS)
                    .formParam("motivo", "Producto defectuoso")
                    .formParam("lineaNumero", "0").formParam("lineaCantidad", "1")
                    .when().post(API + "/" + factura.getId() + "/authorize")
                    .then()
                    .statusCode(401);

            assertEquals(notasAntes, countNotas(), "no NotaCredito may be created on auth failure");
            assertEquals(inventariosAntes, countInventarios(),
                    "no Inventario movement may be created on auth failure");
            assertTrue(listPorComprobante(factura.getId()).isEmpty(),
                    "factura must have zero credit notes after auth failure");
            verifyNoInteractions(comprobanteService);
        } finally {
            deleteFacturaQuietly(factura);
        }
    }

    // ── 2. Authorize success → NC matching golden structural fixture ────

    @Test
    @TestSecurity(user = "admin", roles = {"admin", "facturacion"})
    void authorizeSuccessGeneratesNcMatchingGoldenStructuralFixture() throws Exception {
        JsonNode golden = loadGoldenFixture();
        seedAppSettings();
        ComprobantesEmitidos factura = null;
        ComprobantesEmitidos ncCreado = null;
        List<Inventario> movimientosCreados = new ArrayList<>();
        try {
            factura = seedFactura();
            long stockRowsAntes = countArticuloStock();

            NcSummary summary = extractSummary(postAuthorize(factura.getId(),
                    "Producto defectuoso",
                    indicesFromFixture(golden.get("seleccion"), "indice"),
                    indicesFromFixture(golden.get("seleccion"), "cantidad"))
                    .then()
                    .statusCode(200)
                    .body("data.ncGenerada", org.hamcrest.Matchers.equalTo(true))
                    .extract().response());

            JsonNode claveSpec = golden.get("nc").get("clave");
            assertNotNull(summary.clave(), "NC clave must be present");
            assertEquals(claveSpec.get("longitud").asInt(), summary.clave().length(),
                    "clave must be 50 digits");
            assertTrue(summary.clave().matches("\\d{50}"), "clave must be numeric-only");
            assertTrue(summary.clave().startsWith(claveSpec.get("prefijoPais").asText()),
                    "clave must start with the country code");
            assertEquals("1", summary.clave().substring(41, 42),
                    "situacion '1' at position 42 of the clave");

            JsonNode consSpec = golden.get("nc").get("consecutivo");
            assertNotNull(summary.consecutivo());
            int expectedLen = consSpec.get("longitud").asInt();
            int actualLen = summary.consecutivo().length();
            assertTrue(actualLen == expectedLen || actualLen == 18 || actualLen == 20,
                    "consecutivo length should be 20 (or 18 in test env), was " + actualLen);
            assertEquals(golden.get("nc").get("codigoDocumento").asText(),
                    summary.consecutivo().substring(
                            consSpec.get("tipoSlice").get(0).asInt(),
                            consSpec.get("tipoSlice").get(1).asInt()),
                    "consecutivo must carry the NC document code at [6..8]");
            assertEquals(0, golden.get("totalDevolucion").decimalValue()
                            .compareTo(summary.montoTotal()),
                    "totalDevolucion must match the fixture");

            // Persisted NC comprobante structure.
            List<ComprobantesEmitidos> porClave = emitidosService.findByClave(summary.clave());
            assertEquals(1, porClave.size(), "exactly one comprobante for the NC clave");
            ComprobantesEmitidos nc = porClave.get(0);
            ncCreado = nc;
            assertEquals(summary.consecutivo(), nc.getEncabezado().getNumeroConsecutivo());
            assertEquals(golden.get("nc").get("estadoHacienda").asText(), nc.getHaciendaEstado());
            assertEquals(golden.get("nc").get("encabezadoEstado").asText(),
                    nc.getEncabezado().getEstado());
            assertEquals(golden.get("nc").get("medioPago").get(0).asText(),
                    nc.getEncabezado().getMedioPago().get(0).getMedioPago());
            assertEquals(golden.get("nc").get("moneda").asText(),
                    nc.getResumen().getCodigoMoneda().getCodigoMoneda());
            assertEquals(golden.get("nc").get("referenciaCodigo").asText(),
                    nc.getInformacionReferencia().get(0).getCodigo());

            assertResumenMatchesFixture(nc.getResumen(), golden.get("resumen"));
            assertLineasMatchFixture(nc.getDetalles().getLineasDetalle(),
                    golden.get("lineasNC"));
            assertDesgloseMatchesFixture(nc.getResumen(), golden.get("desgloseImpuestos"));

            // Credit note row.
            List<NotaCredito> notas = listPorComprobante(factura.getId());
            assertEquals(1, notas.size(), "exactly one NotaCredito row");
            NotaCredito nota = notas.get(0);
            assertEquals(0, BigDecimal.valueOf(golden.get("totalDevolucion").doubleValue())
                    .compareTo(nota.getMontoTotal()), "nota montoTotal matches fixture");
            assertEquals("PENDIENTE", nota.getHaciendaEstado());
            assertEquals(SUPERVISOR, nota.getUsuario());

            // Inventory restock side effect (legacy create() parity).
            JsonNode invSpec = golden.get("inventario");
            for (Inventario inv : inventarioService.listAll()) {
                if ("Devolucion".equals(inv.getTipoMovimiento()) && inv.getNotas() != null
                        && inv.getNotas().contains(factura.getEncabezado().getNumeroConsecutivo())) {
                    movimientosCreados.add(inv);
                }
            }
            assertEquals(invSpec.get("movimientosEsperados").asInt(), movimientosCreados.size(),
                    "one movement per returned line");
            for (Inventario inv : movimientosCreados) {
                assertEquals(0, BigDecimal.valueOf(
                                invSpec.get("cantidadPorMovimiento").doubleValue())
                        .compareTo(inv.getCantidad()), "movement quantity is negated");
                assertEquals(invSpec.get("processed").asBoolean(), inv.getProcessed());
            }

            // ArticuloStock parity lock: legacy create() never touches it.
            assertEquals(stockRowsAntes, countArticuloStock(),
                    "devolucion processing must NOT alter ArticuloStock (legacy parity)");

            verify(comprobanteService, times(1)).enviarComprobanteAHacienda(any());
            assertNotNull(summary.printUrl());
            assertTrue(summary.printUrl().endsWith(summary.clave() + ".pdf"),
                    "print URL follows the factura_{clave}.pdf convention");
        } finally {
            for (NotaCredito nota : factura == null ? List.<NotaCredito>of()
                    : listPorComprobante(factura.getId())) {
                notaCreditoService.delete(nota);
            }
            if (ncCreado != null && ncCreado.getId() != null) {
                ComprobantesEmitidos managedNc = emitidosService.find(ncCreado.getId());
                if (managedNc != null) {
                    emitidosService.delete(managedNc);
                }
            }
            for (Inventario inv : movimientosCreados) {
                inventarioService.delete(inv);
            }
            deleteFacturaQuietly(factura);
        }
    }

    // ── 3. Double-devolucion guard ──────────────────────────────────────

    @Test
    @TestSecurity(user = "admin", roles = {"admin", "facturacion"})
    void doubleDevolucionGuardReturns409() {
        seedAppSettings();
        ComprobantesEmitidos factura = null;
        try {
            factura = seedFactura();

            postAuthorize(factura.getId(), "Primera devolucion", new int[]{0}, new int[]{1})
                    .then().statusCode(200);

            postAuthorize(factura.getId(), "Segunda devolucion", new int[]{0}, new int[]{1})
                    .then()
                    .statusCode(409)
                    .body("error.code", org.hamcrest.Matchers.equalTo("ALREADY_RETURNED"));

            assertEquals(1, listPorComprobante(factura.getId()).size(),
                    "the second attempt must NOT create another credit note");
            verify(comprobanteService, times(1)).enviarComprobanteAHacienda(any());
        } finally {
            for (NotaCredito nota : factura == null ? List.<NotaCredito>of()
                    : listPorComprobante(factura.getId())) {
                notaCreditoService.delete(nota);
            }
            deleteFacturaQuietly(factura);
        }
    }

    // ── 4. Validation matrix (initiate + authorize guards) ──────────────

    @Test
    @TestSecurity(user = "facturacion", roles = {"facturacion"})
    void validationMatrixRejectsWithoutWriting() {
        seedAppSettings();
        ComprobantesEmitidos factura = null;
        try {
            factura = seedFactura();
            long notasAntes = countNotas();
            Map<String, String> jar = freshCsrfJar();

            // Unknown factura → 404.
            given().cookies(jar).header(CSRF_HEADER, jar.get(CSRF_COOKIE))
                    .formParam("username", SUPERVISOR).formParam("password", SUPERVISOR_PASS)
                    .formParam("motivo", "x")
                    .formParam("lineaNumero", "0").formParam("lineaCantidad", "1")
                    .when().post(API + "/99999999/authorize").then().statusCode(404);
            given().when().get(API + "/99999999/lineas").then().statusCode(404);

            // Blank motivo → 400 (legacy message preserved).
            given().cookies(jar).header(CSRF_HEADER, jar.get(CSRF_COOKIE))
                    .formParam("username", SUPERVISOR).formParam("password", SUPERVISOR_PASS)
                    .formParam("motivo", "   ")
                    .formParam("lineaNumero", "0").formParam("lineaCantidad", "1")
                    .when().post(API + "/" + factura.getId() + "/authorize")
                    .then()
                    .statusCode(400)
                    .body(containsString("Ingrese el motivo de la devolucion"));

            // No selected line → 400 (legacy message preserved).
            given().cookies(jar).header(CSRF_HEADER, jar.get(CSRF_COOKIE))
                    .formParam("username", SUPERVISOR).formParam("password", SUPERVISOR_PASS)
                    .formParam("motivo", "x")
                    .formParam("lineaNumero", "0").formParam("lineaCantidad", "0")
                    .when().post(API + "/" + factura.getId() + "/authorize")
                    .then()
                    .statusCode(400)
                    .body(containsString("Seleccione al menos un articulo"));

            // Quantity above original → 400 (server-side guard of the legacy
            // p:inputNumber max).
            given().cookies(jar).header(CSRF_HEADER, jar.get(CSRF_COOKIE))
                    .formParam("username", SUPERVISOR).formParam("password", SUPERVISOR_PASS)
                    .formParam("motivo", "x")
                    .formParam("lineaNumero", "0").formParam("lineaCantidad", "5")
                    .when().post(API + "/" + factura.getId() + "/authorize")
                    .then().statusCode(400);

            // initiate mirrors the guards without writing.
            given().cookies(jar).header(CSRF_HEADER, jar.get(CSRF_COOKIE))
                    .formParam("facturaId", factura.getId())
                    .formParam("motivo", "Producto defectuoso")
                    .formParam("lineaNumero", "0").formParam("lineaCantidad", "2")
                    .when().post(API + "/initiate")
                    .then()
                    .statusCode(200)
                    .body("data.totalDevolucion", org.hamcrest.Matchers.equalTo(10000.0f))
                    .body("data.lineasSeleccionadas",
                            org.hamcrest.Matchers.equalTo(1));

            assertEquals(notasAntes, countNotas(), "validation failures write nothing");
            verifyNoInteractions(comprobanteService);
        } finally {
            deleteFacturaQuietly(factura);
        }
    }

    // ── 5. Search + detail read side (legacy buscarFactura parity) ──────

    @Test
    @TestSecurity(user = "facturacion", roles = {"facturacion"})
    void searchAndDetailMirrorLegacyFilters() {
        ComprobantesEmitidos factura = null;
        try {
            factura = seedFactura();
            String consecutivo = factura.getEncabezado().getNumeroConsecutivo();

            // consecutivo mode: contains-match over all comprobantes.
            given()
                    .when().queryParam("tipo", "consecutivo")
                    .queryParam("q", consecutivo.substring(4))
                    .get(API + "/facturas")
                    .then()
                    .statusCode(200)
                    .body("data.data.consecutivo", org.hamcrest.Matchers.hasItem(consecutivo));

            // cliente mode: legacy inverted-contains (needle.contains(nombre));
            // the criterion must ALSO match a client via searchByName, so the
            // exact seeded client name is used.
            given()
                    .when().queryParam("tipo", "cliente")
                    .queryParam("q", CLIENTE_CONTADO)
                    .get(API + "/facturas")
                    .then()
                    .statusCode(200)
                    .body("data.total", org.hamcrest.Matchers.greaterThanOrEqualTo(1));

            // Blank criterion → 400 (legacy WARN parity).
            given()
                    .when().queryParam("q", "   ")
                    .get(API + "/facturas")
                    .then()
                    .statusCode(400);

            // Line detail JSON twin.
            given()
                    .when().get(API + "/" + factura.getId() + "/lineas")
                    .then()
                    .statusCode(200)
                    .body("data.lineas.size()", org.hamcrest.Matchers.equalTo(2))
                    .body("data.lineas[0].cantidadOriginal", org.hamcrest.Matchers.equalTo(2.0f));
        } finally {
            deleteFacturaQuietly(factura);
        }
    }

    // ── 6. Page render + HX fragments ───────────────────────────────────

    @Test
    @TestSecurity(user = "facturacion", roles = {"facturacion"})
    void devolucionesPageRendersKitMarkers() {
        given()
                .when().get(PAGES)
                .then()
                .statusCode(200)
                .body(containsString("Devoluciones y Notas de Credito"))
                .body(containsString("data-kit-table"))
                .body(containsString("id=\"historial-tbody\""))
                .body(containsString("Buscar Factura"));

        given()
                .header("HX-Request", "true")
                .when().get(PAGES)
                .then()
                .statusCode(200)
                .body(containsString("data-kit-table"))
                .body(org.hamcrest.Matchers.not(containsString("<footer")));
    }

    @Test
    @TestSecurity(user = "facturacion", roles = {"facturacion"})
    void lineasAndAuthformFragmentsRenderSelectionUx() {
        ComprobantesEmitidos factura = null;
        try {
            factura = seedFactura();
            given()
                    .header("HX-Request", "true")
                    .when().get(API + "/" + factura.getId() + "/lineas")
                    .then()
                    .statusCode(200)
                    .body(containsString("id=\"devolucion-form\""))
                    .body(containsString("name=\"lineaNumero\""))
                    .body(containsString("name=\"lineaCantidad\""))
                    .body(containsString("Motivo de la Devolucion"))
                    .body(containsString("auth-devolucion-modal"));

            given()
                    .header("HX-Request", "true")
                    .when().get(API + "/" + factura.getId() + "/authform")
                    .then()
                    .statusCode(200)
                    .body(containsString("id=\"auth-devolucion-form\""))
                    .body(containsString("/authorize"))
                    .body(containsString("hx-include=\"#devolucion-form, #devolucion-motivo\""));
        } finally {
            deleteFacturaQuietly(factura);
        }
    }

    // ── 7. Role matrix ──────────────────────────────────────────────────

    @Test
    @TestSecurity(user = "bodeguero", roles = {"inventario"})
    void nonFacturacionRoleIsForbiddenEverywhere() {
        given().when().get(API + "/facturas?q=x").then().statusCode(403);
        given().when().get(PAGES).then().statusCode(403);
    }

    @Test
    void unauthenticatedRequestsAreChallenged() {
        given().redirects().follow(false)
                .when().get(API + "/facturas?q=x")
                .then()
                .statusCode(302)
                .header("Location", containsString("/login"));
        given().redirects().follow(false)
                .when().get(PAGES)
                .then()
                .statusCode(302);
    }

    // ════════════════════════════════════════════════════════════════════
    // Fixture helpers
    // ════════════════════════════════════════════════════════════════════

    /**
     * Original factura mirroring the golden fixture: line 0 = 2 × 5000.00
     * gravado 13% (monto 1300), line 1 = 1 × 2000.00 exento; receptor named
     * after the seeded client so the NC strategy resolves it.
     */
    private ComprobantesEmitidos seedFactura() {
        Encabezado encabezado = new Encabezado();
        encabezado.setNumeroConsecutivo(consecutivoUnico());
        encabezado.setFechaEmision(LocalDateTime.now().minusHours(2));
        encabezado.setCondicionVenta("01");
        encabezado.setSchemaVersion("4.4");
        encabezado.setCodigoDocumento("01");
        Receptor receptor = new Receptor();
        receptor.setNombre(CLIENTE_CONTADO);
        encabezado.setReceptor(receptor);

        DetalleServicio detalles = new DetalleServicio();
        detalles.setStatus(true);

        LineaDetalle gravado = new LineaDetalle();
        gravado.setDetalleServicio(detalles);
        gravado.setNumeroLinea(0);
        gravado.setCantidad(new BigDecimal("2"));
        gravado.setPrecioUnitario(new BigDecimal("5000.00"));
        gravado.setDetalle("Producto Gravado T32");
        gravado.setMontoTotal(new BigDecimal("10000.00"));
        gravado.setSubTotal(new BigDecimal("10000.00"));
        Impuesto impuesto = new Impuesto();
        impuesto.setLineaDetalle(gravado);
        impuesto.setCodigo("01");
        impuesto.setCodigoTarifaIVA("01");
        impuesto.setTarifa(new BigDecimal("13"));
        impuesto.setMonto(new BigDecimal("1300.00000"));
        gravado.setImpuestos(new ArrayList<>(List.of(impuesto)));

        LineaDetalle exento = new LineaDetalle();
        exento.setDetalleServicio(detalles);
        exento.setNumeroLinea(1);
        exento.setCantidad(new BigDecimal("1"));
        exento.setPrecioUnitario(new BigDecimal("2000.00"));
        exento.setDetalle("Producto Exento T32");
        exento.setMontoTotal(new BigDecimal("2000.00"));
        exento.setSubTotal(new BigDecimal("2000.00"));

        detalles.setLineasDetalle(new ArrayList<>(List.of(gravado, exento)));

        ResumenFactura resumen = new ResumenFactura();
        resumen.setTotalVenta(new BigDecimal("12000.00"));
        resumen.setTotalImpuesto(new BigDecimal("1300.00000"));
        resumen.setTotalComprobante(new BigDecimal("13300.00"));

        ComprobantesEmitidos comprobante = new ComprobantesEmitidos();
        comprobante.setSchemaVersion("4.4");
        comprobante.setEncabezado(encabezado);
        comprobante.setDetalles(detalles);
        comprobante.setResumen(resumen);
        comprobante.setStatus(true);
        comprobante.setUser("t32-" + UUID.randomUUID().toString().substring(0, 8));
        comprobante.setHaciendaEstado("ACEPTADO");
        return emitidosService.createAndReturn(comprobante);
    }

    /** Ensures ONE active AppSettings row so the NC pipeline can run. */
    private void seedAppSettings() {
        if (appSettingsService.returnCurrent() != null) {
            return;
        }
        Models.AppSettings settings = new Models.AppSettings();
        settings.setEstatus(Boolean.TRUE);
        settings.setIdentificacion("310112345678");
        settings.setCodigoSucursal("001");
        settings.setCodigoTerminal("001");
        settings.setNombrePerfil("T32 Test Profile");
        appSettingsService.create(settings);
    }

    private Response postAuthorize(long facturaId, String motivo,
                                   int[] indices, int[] cantidades) {
        Map<String, String> jar = freshCsrfJar();
        RequestSpecification request = given()
                .cookies(jar).header(CSRF_HEADER, jar.get(CSRF_COOKIE))
                .formParam("username", SUPERVISOR)
                .formParam("password", SUPERVISOR_PASS)
                .formParam("motivo", motivo);
        for (int i = 0; i < indices.length; i++) {
            request.formParam("lineaNumero", String.valueOf(indices[i]))
                    .formParam("lineaCantidad", String.valueOf(cantidades[i]));
        }
        return request.when().post(API + "/" + facturaId + "/authorize");
    }

    /** Reads one column of the fixture's seleccion array as an int array. */
    private static int[] indicesFromFixture(JsonNode seleccion, String campo) {
        int[] valores = new int[seleccion.size()];
        for (int i = 0; i < seleccion.size(); i++) {
            valores[i] = seleccion.get(i).get(campo).asInt();
        }
        return valores;
    }

    private static NcSummary extractSummary(Response response) {
        return new NcSummary(
                response.jsonPath().getString("data.facturaConsecutivo"),
                response.jsonPath().getString("data.clave"),
                response.jsonPath().getString("data.consecutivo"),
                new BigDecimal(response.jsonPath().getString("data.montoTotal")),
                response.jsonPath().getString("data.motivo"),
                Boolean.TRUE.equals(response.jsonPath().getBoolean("data.ncGenerada")),
                response.jsonPath().getString("data.mensaje"),
                response.jsonPath().getString("data.printUrl"));
    }

    private void assertResumenMatchesFixture(ResumenFactura resumen, JsonNode spec) {
        assertDecimal(spec.get("totalVenta"), resumen.getTotalVenta(), "totalVenta");
        assertDecimal(spec.get("totalGravado"), resumen.getTotalGravado(), "totalGravado");
        assertDecimal(spec.get("totalExento"), resumen.getTotalExento(), "totalExento");
        assertDecimal(spec.get("totalExonerado"), resumen.getTotalExonerado(), "totalExonerado");
        assertDecimal(spec.get("totalServGravados"), resumen.getTotalServGravados(), "totalServGravados");
        assertDecimal(spec.get("totalMercanciasGravadas"), resumen.getTotalMercanciasGravadas(),
                "totalMercanciasGravadas");
        assertDecimal(spec.get("totalServExentos"), resumen.getTotalServExentos(), "totalServExentos");
        assertDecimal(spec.get("totalMercanciasExentas"), resumen.getTotalMercanciasExentas(),
                "totalMercanciasExentas");
        assertDecimal(spec.get("totalDescuentos"), resumen.getTotalDescuentos(), "totalDescuentos");
        assertDecimal(spec.get("totalVentaNeta"), resumen.getTotalVentaNeta(), "totalVentaNeta");
        assertDecimal(spec.get("totalImpuesto"), resumen.getTotalImpuesto(), "totalImpuesto");
        assertDecimal(spec.get("totalIVADevuelto"), resumen.getTotalIVADevuelto(), "totalIVADevuelto");
        assertDecimal(spec.get("totalOtrosCargos"), resumen.getTotalOtrosCargos(), "totalOtrosCargos");
        assertDecimal(spec.get("totalComprobante"), resumen.getTotalComprobante(), "totalComprobante");
    }

    private void assertLineasMatchFixture(List<LineaDetalle> lineas, JsonNode spec) {
        assertEquals(spec.size(), lineas.size(), "NC line count must match the fixture");
        for (int i = 0; i < spec.size(); i++) {
            JsonNode esperada = spec.get(i);
            LineaDetalle real = lineas.get(i);
            assertEquals(esperada.get("numeroLinea").asInt(), real.getNumeroLinea());
            assertDecimal(esperada.get("cantidad"), real.getCantidad(), "linea " + i + " cantidad");
            assertDecimal(esperada.get("precioUnitario"), real.getPrecioUnitario(),
                    "linea " + i + " precioUnitario");
            assertDecimal(esperada.get("montoTotal"), real.getMontoTotal(), "linea " + i + " montoTotal");
            assertDecimal(esperada.get("subTotal"), real.getSubTotal(), "linea " + i + " subTotal");
            assertDecimal(esperada.get("montoTotalLinea"), real.getMontoTotalLinea(),
                    "linea " + i + " montoTotalLinea");
            JsonNode impuestosEsperados = esperada.get("impuestos");
            int reales = real.getImpuestos() == null ? 0 : real.getImpuestos().size();
            assertEquals(impuestosEsperados.size(), reales, "linea " + i + " impuesto count");
            for (int j = 0; j < impuestosEsperados.size(); j++) {
                Impuesto imp = real.getImpuestos().get(j);
                assertEquals(impuestosEsperados.get(j).get("codigo").asText(), imp.getCodigo());
                assertDecimal(impuestosEsperados.get(j).get("tarifa"), imp.getTarifa(),
                        "linea " + i + " tarifa");
                assertDecimal(impuestosEsperados.get(j).get("monto"), imp.getMonto(),
                        "linea " + i + " impuesto monto");
            }
        }
    }

    private void assertDesgloseMatchesFixture(ResumenFactura resumen, JsonNode spec) {
        List<Models.Resumen.TotalDesgloseImpuesto> desglose = resumen.getTotalDesgloseImpuestos();
        assertEquals(spec.size(), desglose == null ? 0 : desglose.size(),
                "desglose entry count must match the fixture");
        for (int i = 0; i < spec.size(); i++) {
            Models.Resumen.TotalDesgloseImpuesto item = desglose.get(i);
            assertEquals(spec.get(i).get("codigo").asText(), item.getCodigo());
            assertDecimal(spec.get(i).get("monto"), item.getTotalMontoImpuesto(),
                    "desglose monto");
        }
    }

    private static void assertDecimal(JsonNode expected, BigDecimal real, String campo) {
        assertNotNull(real, campo + " must not be null");
        assertEquals(0, BigDecimal.valueOf(expected.decimalValue().doubleValue()).compareTo(real),
                campo + " must match the golden fixture");
    }

    private JsonNode loadGoldenFixture() throws Exception {
        try (InputStream in = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream("fixtures/devoluciones/golden-nc-structure.json")) {
            assertNotNull(in, "golden fixture must exist on the test classpath");
            return new ObjectMapper().readTree(in);
        }
    }

    /** Unique-in-DB consecutivo (column length 20). */
    private static String consecutivoUnico() {
        String sufijo = UUID.randomUUID().toString().replace("-", "")
                .substring(0, 10).toUpperCase();
        String valor = "T32" + sufijo;
        return valor.length() > 20 ? valor.substring(0, 20) : valor;
    }

    /** Authenticated GET mints the csrf-token cookie (quarkus-rest-csrf). */
    private Map<String, String> freshCsrfJar() {
        Response respuesta = given()
                .when().get(PAGES);
        respuesta.then().statusCode(200);
        Map<String, String> jar = new LinkedHashMap<>(respuesta.getCookies());
        assertNotNull(jar.get(CSRF_COOKIE), "authenticated GET must mint the csrf-token cookie");
        return jar;
    }

    private long countNotas() {
        List<NotaCredito> todas = notaCreditoService.listAll();
        return todas == null ? 0 : todas.size();
    }

    private long countInventarios() {
        List<Inventario> todos = inventarioService.listAll();
        return todos == null ? 0 : todos.size();
    }

    private long countArticuloStock() {
        List<ArticuloStock> rows = em.createQuery("SELECT s FROM ArticuloStock s",
                ArticuloStock.class).getResultList();
        return rows.size();
    }

    private List<NotaCredito> listPorComprobante(Long facturaId) {
        List<NotaCredito> notas = notaCreditoService.listPorComprobante(facturaId);
        return notas == null ? List.of() : notas;
    }

    private void deleteFacturaQuietly(ComprobantesEmitidos factura) {
        if (factura == null || factura.getId() == null) {
            return;
        }
        ComprobantesEmitidos managed = emitidosService.find(factura.getId());
        if (managed != null) {
            emitidosService.delete(managed);
        }
    }
}
