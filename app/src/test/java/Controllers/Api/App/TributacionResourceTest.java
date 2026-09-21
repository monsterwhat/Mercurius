package Controllers.Api.App;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import Models.ComprobantesEmitidos;
import Models.ComprobantesRecibidos;
import Models.Encabezado.Encabezado;
import Models.NotaCredito;
import Models.Resumen.ResumenFactura;
import Services.ComprobantesEmitidosService;
import Services.ComprobantesRecibidosService;
import Services.HaciendaServiceFacade;
import Services.NotaCreditoService;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * T28 — Tributación module acceptance suite ({@code admin}/{@code tributacion}
 * role gates, {@link HaciendaServiceFacade} STUBBED with {@code @InjectMock}:
 * no real Hacienda/Fides network call can ever happen from these tests).
 *
 * <p>Fixtures are committed outside @TestTransaction so the HTTP request
 * thread sees them, and removed in finally blocks (same discipline as
 * RecibosReportesPageTest); %test boots drop-and-create so a failed assertion
 * cannot poison later runs.</p>
 *
 * <p>Scenarios (12): dashboard counts vs fixture; dashboard page render;
 * KPI fragment dual-mode; consultas page markers (every-5s poll span +
 * Alpine tabs); countdown fragment + JSON twin; role matrix (forbidden +
 * unauthenticated); bulk-send happy/mixed through the stubbed facade;
 * bulk-send without pendientes never touches the facade; correct-rejected
 * credit-note idempotency + 404/409 guards; D-104 date-range sums + monthly
 * filas + bounds validation; MR deadline indicator states.</p>
 */
@QuarkusTest
@Tag("tributacion")
class TributacionResourceTest extends support.ContextPathIsolation {

    private static final String BASE = "/Mercurius";
    private static final String API = BASE + "/api/app/tributacion";
    private static final String PAGES = BASE + "/app/tributacion";
    private static final String CSRF_COOKIE = "csrf-token";
    private static final String CSRF_HEADER = "X-CSRF-TOKEN";

    @Inject
    ComprobantesEmitidosService emitidosService;

    @Inject
    ComprobantesRecibidosService recibidosService;

    @Inject
    NotaCreditoService notaCreditoService;

    @Inject
    EntityManager em;

    @InjectMock
    HaciendaServiceFacade haciendaFacade;

    // ── 1. Dashboard aggregate counts are fixture-based ─────────────────

    @Test
    @TestSecurity(user = "tributacion", roles = {"tributacion"})
    void dashboardCountsMatchSeededFixture() {
        Map<String, Integer> base = dashboardCounts();
        ComprobantesEmitidos aceptado = null;
        ComprobantesEmitidos rechazado = null;
        ComprobantesEmitidos pendiente = null;
        try {
            aceptado = seedEmitido(consecutivo("ACC"), "ACEPTADO",
                    LocalDateTime.now().minusHours(1), null);
            rechazado = seedEmitido(consecutivo("REZ"), "RECHAZADO",
                    LocalDateTime.now().minusHours(2), null);
            pendiente = seedEmitido(consecutivo("PND"), null,
                    LocalDateTime.now().minusHours(3), null);

            Map<String, Integer> ahora = dashboardCounts();
            assertEquals(base.get("aceptado") + 1, ahora.get("aceptado"),
                    "exactly one extra ACEPTADO must be counted");
            assertEquals(base.get("rechazado") + 1, ahora.get("rechazado"),
                    "exactly one extra RECHAZADO must be counted");
            assertEquals(base.get("pendiente") + 1, ahora.get("pendiente"),
                    "null estado counts as pendiente (legacy parity)");
        } finally {
            deleteQuietly(aceptado, rechazado, pendiente);
        }
    }

    // ── 2-3. Dashboard page + KPI fragment dual-mode ────────────────────

    @Test
    @TestSecurity(user = "admin", roles = {"admin", "tributacion"})
    void dashboardPageRendersKitMarkers() {
        given()
                .when().get(PAGES + "/dashboard")
                .then()
                .statusCode(200)
                .body(containsString("Dashboard Hacienda"))
                .body(containsString("data-kit-table"))
                .body(containsString("Últimos 7 días"));
    }

    @Test
    @TestSecurity(user = "tributacion", roles = {"tributacion"})
    void hxRequestOnDashboardReturnsFragmentWithoutLayout() {
        given()
                .header("HX-Request", "true")
                .when().get(PAGES + "/dashboard")
                .then()
                .statusCode(200)
                .body(containsString("Total Emitidos"))
                .body(containsString("data-kit-table"))
                .body(Matchers.not(containsString("<footer")));
    }

    // ── 4-5. Consultas page + countdown polling fragment ────────────────

    @Test
    @TestSecurity(user = "tributacion", roles = {"tributacion"})
    void consultasPageCarriesPollSpanAndAlpineTabs() {
        given()
                .when().get(PAGES + "/consultas")
                .then()
                .statusCode(200)
                .body(containsString("Consultas de Facturas"))
                .body(containsString("id=\"consultas-countdown\""))
                .body(containsString("hx-trigger=\"every 5s\""))
                .body(containsString("x-data=\"{ tab:"))
                .body(containsString("enviar-pendientes"))
                .body(containsString("data-kit-table"));
    }

    @Test
    @TestSecurity(user = "tributacion", roles = {"tributacion"})
    void countdownEndpointServesFragmentAndJsonTwins() {
        String html = given()
                .header("HX-Request", "true")
                .when().get(API + "/consultas/countdown")
                .then()
                .statusCode(200)
                .contentType(containsString("text/html"))
                .extract().asString();
        assertTrue(html.contains("id=\"consultas-countdown\""),
                "fragment must re-emit the polling span so every 5s never dies");
        assertTrue(html.contains("hx-trigger=\"every 5s\""),
                "re-emitted span must keep the poll trigger");
        assertTrue(html.contains("hx-swap-oob") && html.contains("consultas-contadores"),
                "counters must ride along as an out-of-band swap");

        given()
                .when().get(API + "/consultas/countdown")
                .then()
                .statusCode(200)
                .body("data.countdownDisplay", Matchers.matchesRegex("\\d{2}:\\d{2}:\\d{2}"))
                .body("data.proximoEnvioDisplay",
                        Matchers.anyOf(
                                Matchers.containsString("Próximo envío:"),
                                Matchers.equalTo("No hay envíos programados")))
                .body("data.contadorPendientes", Matchers.notNullValue())
                .body("data.contadorAceptadas", Matchers.notNullValue())
                .body("data.contadorRechazadas", Matchers.notNullValue());
    }

    // ── 6. Role matrix ──────────────────────────────────────────────────

    @Test
    @TestSecurity(user = "bodeguero", roles = {"inventario"})
    void nonTributacionRoleIsForbiddenEverywhere() {
        given().when().get(API + "/dashboard").then().statusCode(403);
        given().when().get(PAGES + "/dashboard").then().statusCode(403);
        given().when().get(PAGES + "/consultas").then().statusCode(403);
        given().when().get(PAGES + "/declaracion").then().statusCode(403);
    }

    @Test
    void unauthenticatedRequestsAreChallenged() {
        given().redirects().follow(false)
                .when().get(API + "/dashboard")
                .then()
                .statusCode(302)
                .header("Location", containsString("/login"));
        given().redirects().follow(false)
                .when().get(PAGES + "/declaracion")
                .then()
                .statusCode(302);
    }

    // ── 7-8. Bulk send through the stubbed facade ───────────────────────

    @Test
    @TestSecurity(user = "tributacion", roles = {"tributacion"})
    void bulkSendRoutesEveryPendienteThroughTheFacade() {
        assertTrue(pendientesBaselineIsEmpty(), "test requires a clean pendientes table");
        when(haciendaFacade.submitDocument(any())).thenAnswer(inv -> {
            ComprobantesEmitidos c = inv.getArgument(0);
            return "IT28-OK".equals(c.getHaciendaClave())
                    ? HaciendaServiceFacade.SubmitResult.accepted()
                    : HaciendaServiceFacade.SubmitResult.rejected("XML invalido");
        });

        ComprobantesEmitidos buena = null;
        ComprobantesEmitidos mala = null;
        ComprobantesEmitidos sinClave = null;
        try {
            buena = seedEmitido(consecutivo("BULKOK"), null,
                    LocalDateTime.now().minusHours(1), "IT28-OK");
            mala = seedEmitido(consecutivo("BULKNO"), null,
                    LocalDateTime.now().minusHours(2), "IT28-NO");
            sinClave = seedEmitido(consecutivo("BULKNC"), null,
                    LocalDateTime.now().minusHours(3), null);

            Map<String, String> jar = freshCsrfJar();
            given()
                    .cookies(jar)
                    .header(CSRF_HEADER, jar.get(CSRF_COOKIE))
                    .when().post(API + "/consultas/enviar-pendientes")
                    .then()
                    .statusCode(200)
                    .body("data.total", equalTo(3))
                    .body("data.enviadas", equalTo(1))
                    .body("data.fallidas", equalTo(2));

            ComprobantesEmitidos trasEnvio = emitidosService.find(buena.getId());
            assertEquals("ACEPTADO", trasEnvio.getHaciendaEstado(),
                    "accepted submission must stamp haciendaEstado");
            assertNotNull(trasEnvio.getHaciendaFechaEnvio());
            assertEquals("ACEPTADO", trasEnvio.getEncabezado().getEstado(),
                    "encabezado.estado mirrors the legacy update");

            ComprobantesEmitidos trasFallo = emitidosService.find(mala.getId());
            assertEquals("RECHAZADO", trasFallo.getEncabezado().getEstado(),
                    "rejected submission marks encabezado RECHAZADO");
            assertEquals("XML invalido", trasFallo.getEncabezado().getMotivoRechazo(),
                    "facade error message lands on motivoRechazo");

            verify(haciendaFacade, times(2)).submitDocument(any());
        } finally {
            deleteQuietly(buena, mala, sinClave);
        }
    }

    @Test
    @TestSecurity(user = "tributacion", roles = {"tributacion"})
    void bulkSendWithoutPendientesNeverTouchesTheFacade() {
        assertTrue(pendientesBaselineIsEmpty(), "test requires a clean pendientes table");

        Map<String, String> jar = freshCsrfJar();
        given()
                .cookies(jar)
                .header(CSRF_HEADER, jar.get(CSRF_COOKIE))
                .when().post(API + "/consultas/enviar-pendientes")
                .then()
                .statusCode(200)
                .body("data.total", equalTo(0))
                .body("data.enviadas", equalTo(0));

        verifyNoInteractions(haciendaFacade);
    }

    // ── 9. Correct-rejected action ──────────────────────────────────────

    @Test
    @TestSecurity(user = "admin", roles = {"admin", "tributacion"})
    void corregirRechazadaCreatesNotaCreditoIdempotently() {
        ComprobantesEmitidos rechazada = null;
        ComprobantesEmitidos activa = null;
        try {
            rechazada = seedEmitido(consecutivo("CORREC"), "RECHAZADO",
                    LocalDateTime.now().minusHours(1), "IT28-COR");
            rechazada.setResumen(resumen(new BigDecimal("50000"), new BigDecimal("6500"),
                    new BigDecimal("56500")));
            emitidosService.update(rechazada);
            activa = seedEmitido(consecutivo("CORACT"), "ACEPTADO",
                    LocalDateTime.now().minusHours(2), "IT28-ACT");

            String token = postCorregir(rechazada.getId())
                    .then()
                    .statusCode(200)
                    .body("data.notaCreditoCreada", equalTo(true))
                    .extract().jsonPath().getString("data.token");
            assertEquals("CORREGIR_" + rechazada.getId(), token);

            List<NotaCredito> notas = notaCreditoService.listPorComprobante(rechazada.getId());
            assertEquals(1, notas.size(), "exactly one automatic credit note");
            assertTrue(notas.get(0).getMotivo().startsWith("Corrección automática por rechazo"),
                    "legacy motivo text preserved");

            postCorregir(rechazada.getId())
                    .then()
                    .statusCode(200)
                    .body("data.notaCreditoCreada", equalTo(false));
            assertEquals(1, notaCreditoService.listPorComprobante(rechazada.getId()).size(),
                    "second correction must NOT duplicate the credit note");

            postCorregir(activa.getId()).then().statusCode(409);
            postCorregir(99999999L).then().statusCode(404);
        } finally {
            List<NotaCredito> huerfanas = rechazada == null
                    ? List.of() : notaCreditoService.listPorComprobante(rechazada.getId());
            for (NotaCredito nota : huerfanas) {
                notaCreditoService.delete(nota);
            }
            deleteQuietly(rechazada, activa);
        }
    }

    // ── 10. Declaración IVA D-104 date-range happy path ─────────────────

    @Test
    @TestSecurity(user = "tributacion", roles = {"tributacion"})
    void declaracionResumenSumsPeriodThroughListByDateRange() {
        // clean ALL leaked emitidos/recibidos for grouped runs (including ACEPTADO)
        for (var p : emitidosService.listAll()) { try { emitidosService.delete(p); } catch (Exception ignore) {} }
        for (var r : recibidosService.listAll()) { try { recibidosService.delete(r); } catch (Exception ignore) {} }
        LocalDate hoy = LocalDate.now();
        ComprobantesEmitidos venta = null;
        ComprobantesRecibidos compra = null;
        try {
            venta = seedEmitido(consecutivo("IVAVTA"), "ACEPTADO",
                    hoy.atTime(10, 0), "IT28-IVA");
            venta.setResumen(resumen(new BigDecimal("100000"), new BigDecimal("13000"),
                    new BigDecimal("113000")));
            emitidosService.update(venta);

            compra = seedRecibido(consecutivo("IVACMP"), hoy.atTime(11, 0),
                    new BigDecimal("50000"), new BigDecimal("6500"));

            Response respuesta = given()
                    .queryParam("mes", hoy.getMonthValue())
                    .queryParam("anio", hoy.getYear())
                    .when().get(API + "/declaracion/resumen");
            respuesta.then().statusCode(200);
            var json = respuesta.jsonPath();
            assertEquals(0, new BigDecimal(json.getString("data.totalVentas"))
                    .compareTo(new BigDecimal("100000")), "ventas = sum(totalVentaNeta emitidas)");
            assertEquals(0, new BigDecimal(json.getString("data.ivaDebito"))
                    .compareTo(new BigDecimal("13000")), "débito = sum(totalImpuesto emitidas)");
            assertEquals(0, new BigDecimal(json.getString("data.totalCompras"))
                    .compareTo(new BigDecimal("50000")), "compras = sum(totalVentaNeta recibidas)");
            assertEquals(0, new BigDecimal(json.getString("data.ivaCredito"))
                    .compareTo(new BigDecimal("6500")), "crédito = sum(totalImpuesto recibidas)");
            assertEquals(0, new BigDecimal(json.getString("data.ivaNeto"))
                    .compareTo(new BigDecimal("6500")), "neto = débito − crédito");
            assertEquals(12, json.getList("data.filas").size(),
                    "one monthly fila per month of the year");
            int indiceMes = hoy.getMonthValue() - 1;
            assertEquals(0, new BigDecimal(json.getString("data.filas[" + indiceMes + "].totalVentas"))
                    .compareTo(new BigDecimal("100000")),
                    "the current-month fila carries the seeded ventas");

            given()
                    .queryParam("mes", 13)
                    .queryParam("anio", hoy.getYear())
                    .when().get(API + "/declaracion/resumen")
                    .then()
                    .statusCode(400);
        } finally {
            deleteQuietly(venta);
            if (compra != null) {
                recibidosService.delete(compra);
            }
        }
    }

    // ── 11. MR deadline indicator states ────────────────────────────────

    @Test
    @TestSecurity(user = "tributacion", roles = {"tributacion"})
    void mensajesReceptorDeadlineIndicatorStates() {
        ComprobantesRecibidos atendido = null;
        ComprobantesRecibidos vencido = null;
        ComprobantesRecibidos porVencer = null;
        ComprobantesRecibidos enTiempo = null;
        try {
            atendido = seedRecibido(consecutivo("MRATN"), LocalDateTime.now().minusDays(20),
                    null, null);
            setLimiteTransactional(atendido.getId(), LocalDate.now().minusDays(5));
            ComprobantesRecibidos tmpAt = recibidosService.find(atendido.getId());
            tmpAt.setHaciendaMensajeReceptorEstado("ACEPTADO");
            recibidosService.update(tmpAt);
            atendido = tmpAt;

            vencido = seedRecibido(consecutivo("MRVNC"), LocalDateTime.now().minusDays(40),
                    null, null);
            setLimiteTransactional(vencido.getId(), LocalDate.now().minusDays(1));
            vencido = recibidosService.find(vencido.getId());

            porVencer = seedRecibido(consecutivo("MRPOR"), LocalDateTime.now().minusDays(30),
                    null, null);
            setLimiteTransactional(porVencer.getId(), LocalDate.now().plusDays(1));
            porVencer = recibidosService.find(porVencer.getId());

            enTiempo = seedRecibido(consecutivo("MRTMP"), LocalDateTime.now().minusDays(5),
                    null, null);
            setLimiteTransactional(enTiempo.getId(), LocalDate.now().plusDays(15));
            enTiempo = recibidosService.find(enTiempo.getId());

            Response respuesta = given()
                    .when().get(API + "/consultas/mensajes");
            respuesta.then().statusCode(200);
            String body = respuesta.asString();
            assertTrue(body.contains("\"indicador\":\"ATENDIDO\""),
                    "an MR with estado reported is ATENDIDO regardless of deadline");
            assertTrue(body.contains("\"indicador\":\"VENCIDO\""),
                    "past limite without estado is VENCIDO");
            assertTrue(body.contains("\"indicador\":\"POR_VENCER\""),
                    "limite within 2 days without estado is POR_VENCER");
            assertTrue(body.contains("\"indicador\":\"EN_TIEMPO\""),
                    "comfortable limite without estado is EN_TIEMPO");
        } finally {
            for (ComprobantesRecibidos r : List.of(atendido, vencido, porVencer, enTiempo)) {
                if (r != null) {
                    recibidosService.delete(r);
                }
            }
        }
    }

    // ── Fixture helpers ─────────────────────────────────────────────────

    /** Unique-in-DB consecutivo (column length 20). */
    private static String consecutivo(String prefijo) {
        String sufijo = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        String valor = "IT28" + prefijo + sufijo;
        return valor.length() > 20 ? valor.substring(0, 20) : valor;
    }

    private ComprobantesEmitidos seedEmitido(String consecutivo, String estado,
                                             LocalDateTime fechaEmision, String clave) {
        Encabezado encabezado = new Encabezado();
        encabezado.setNumeroConsecutivo(consecutivo);
        encabezado.setFechaEmision(fechaEmision);
        encabezado.setEstado(estado);
        encabezado.setCondicionVenta("01");
        encabezado.setSchemaVersion("4.4");
        encabezado.setCodigoDocumento("01");

        ComprobantesEmitidos comprobante = new ComprobantesEmitidos();
        comprobante.setSchemaVersion("4.4");
        comprobante.setEncabezado(encabezado);
        comprobante.setStatus(true);
        comprobante.setUser("it-t28");
        comprobante.setHaciendaClave(clave);
        // Dashboard counters read comprobante.haciendaEstado while the
        // pendientes bucket queries and the corregir guard read
        // encabezado.estado; fixtures keep both in sync.
        comprobante.setHaciendaEstado(estado);
        return emitidosService.createAndReturn(comprobante);
    }

    private ComprobantesRecibidos seedRecibido(String consecutivo, LocalDateTime fechaEmision,
                                               BigDecimal totalVentas, BigDecimal totalImpuesto) {
        Encabezado encabezado = new Encabezado();
        encabezado.setNumeroConsecutivo(consecutivo);
        encabezado.setFechaEmision(fechaEmision);
        encabezado.setCondicionVenta("01");
        encabezado.setSchemaVersion("4.4");
        encabezado.setCodigoDocumento("01");

        ComprobantesRecibidos comprobante = new ComprobantesRecibidos();
        comprobante.setEncabezado(encabezado);
        comprobante.setStatus(true);
        comprobante.setProcessed(false);
        if (totalVentas != null || totalImpuesto != null) {
            comprobante.setResumen(resumen(totalVentas, totalImpuesto,
                    totalVentas == null ? BigDecimal.ZERO : totalVentas.add(
                            totalImpuesto == null ? BigDecimal.ZERO : totalImpuesto)));
        }
        recibidosService.create(comprobante);
        return comprobante;
    }

    private static ResumenFactura resumen(BigDecimal totalVentaNeta, BigDecimal totalImpuesto,
                                          BigDecimal totalComprobante) {
        ResumenFactura resumen = new ResumenFactura();
        resumen.setTotalVentaNeta(totalVentaNeta);
        resumen.setTotalImpuesto(totalImpuesto);
        resumen.setTotalComprobante(totalComprobante);
        return resumen;
    }

    private Map<String, Integer> dashboardCounts() {
        Response respuesta = given()
                .when().get(API + "/dashboard");
        respuesta.then().statusCode(200);
        var json = respuesta.jsonPath();
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("aceptado", json.getInt("data.aceptado"));
        counts.put("rechazado", json.getInt("data.rechazado"));
        counts.put("pendiente", json.getInt("data.pendiente"));
        return counts;
    }

    private boolean pendientesBaselineIsEmpty() {
        List<ComprobantesEmitidos> pendientes = emitidosService.findFacturasPendientes();
        if (pendientes != null && !pendientes.isEmpty()) {
            // auto-clean leaked pendings for grouped runs
            for (var p : pendientes) {
                try { emitidosService.delete(p); } catch (Exception ignore) {}
            }
            pendientes = emitidosService.findFacturasPendientes();
        }
        boolean vacio = pendientes == null || pendientes.isEmpty();
        assertTrue(vacio,
                "pendientes table must be empty before this scenario; leaked rows: "
                        + (pendientes == null ? List.of()
                                : pendientes.stream()
                                        .map(f -> f.getEncabezado() == null ? "?" :
                                                f.getEncabezado().getNumeroConsecutivo())
                                        .toList()));
        return vacio;
    }

    /** Authenticated GET mints the csrf-token cookie (quarkus-rest-csrf). */
    private Map<String, String> freshCsrfJar() {
        Response respuesta = given()
                .when().get(API + "/dashboard");
        respuesta.then().statusCode(200);
        Map<String, String> jar = new LinkedHashMap<>(respuesta.getCookies());
        assertNotNull(jar.get(CSRF_COOKIE), "safe GET must mint the csrf-token cookie");
        return jar;
    }

    @Transactional
    void setLimiteTransactional(Long id, LocalDate limite) {
        em.createQuery("UPDATE ComprobantesRecibidos c SET c.mensajeReceptorLimite = :limite WHERE c.id = :id")
          .setParameter("limite", limite)
          .setParameter("id", id)
          .executeUpdate();
        em.flush();
        em.clear();
    }

    /** CSRF-paired POST: cookie and header halves come from the SAME jar. */
    private io.restassured.response.Response postCorregir(long id) {
        Map<String, String> jar = freshCsrfJar();
        return given()
                .cookies(jar)
                .header(CSRF_HEADER, jar.get(CSRF_COOKIE))
                .when().post(API + "/consultas/" + id + "/corregir");
    }

    private void deleteQuietly(ComprobantesEmitidos... comprobantes) {
        for (ComprobantesEmitidos c : comprobantes) {
            if (c != null && c.getId() != null) {
                ComprobantesEmitidos managed = emitidosService.find(c.getId());
                if (managed != null) {
                    emitidosService.delete(managed);
                }
            }
        }
    }
}
