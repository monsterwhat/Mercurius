package Controllers.Api.App;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;

import Models.ComprobantesEmitidos;
import Models.Encabezado.Emisor;
import Models.Encabezado.Encabezado;
import Models.Registros.Alertas;
import Models.Resumen.ResumenFactura;
import Services.AlertasService;
import Services.AppSettingsService;
import Services.ComprobantesEmitidosService;
import Services.HaciendaServiceFacade;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * T27 — Recibos module acceptance suite ({@code admin}/{@code facturacion}
 * role gates; four-bucket board over {@link ComprobantesEmitidosService};
 * pay/process/accept/reject/delete/toggle actions delegating to existing
 * service methods). The Hacienda boundary is STUBBED via {@code @InjectMock}
 * for the process-success scenario — no real Hacienda/Fides network call can
 * ever happen from these tests; the offline short-circuit path (no hacienda
 * clave) is exercised against the REAL service.
 *
 * <p><b>Auth recipe</b> (FacturasRecibidasResourceTest parity): form login
 * over RestAssured with the seeded admin/admin123 user; every mutating call
 * carries {@code X-CSRF-TOKEN} from the CSRF cookie issued on the login page
 * GET.</p>
 *
 * <p><b>Fixture discipline:</b> rows are seeded programmatically through the
 * production service (createAndReturn, T28/T36 parity) with UNIQUE
 * consecutivos/emisores per scenario so assertions are exact even in the
 * shared %test database; every scenario deletes its rows in a finally block.</p>
 *
 * <p>Scenarios (17): todas bucket + DTO parity; pagadas predicate;
 * procesadas predicate; vencidos legacy overdue-credit rule; paging/sort
 * contract; global filter q; stats/list consistency; pay happy path (flip +
 * audit alert + bucket move); pay guards incl. commit-20d3cde credit-note
 * hide-pay regression; /pagar north-star alias; process offline delegation;
 * process success through stubbed Hacienda facade; accept on credit notes +
 * reject bucket move/motivo; delete port; toggle port; role matrix +
 * unauthenticated challenge; page/fragment markers.</p>
 */
@QuarkusTest
@Tag("recibos")
class RecibosResourceTest extends support.ContextPathIsolation {

    private static final String BASE = "/Mercurius";
    private static final String API = BASE + "/api/app/recibos";
    private static final String PAGE = BASE + "/app/recibos";

    @Inject
    ComprobantesEmitidosService emitidosService;

    @Inject
    AlertasService alertas;

    @Inject
    AppSettingsService appSettingsService;

    /** Hacienda boundary stub for the process-success scenario only. */
    @InjectMock
    HaciendaServiceFacade haciendaServiceFacade;

    // ── Auth helpers (FacturasRecibidasResourceTest parity) ─────────────

    private static Map<String, String> adminSession() {
        Response loginPage = given().redirects().follow(false)
                .when().get(BASE + "/login");
        loginPage.then().statusCode(200);
        Map<String, String> cookies = new HashMap<>(loginPage.getCookies());

        Response login = given().redirects().follow(false)
                .cookies(cookies)
                .contentType(ContentType.URLENC)
                .formParam("j_username", "admin")
                .formParam("j_password", "admin123")
                .when().post(BASE + "/j_security_check");
        login.then().statusCode(302);
        cookies.putAll(login.getCookies());
        return cookies;
    }

    private static RequestSpecification authed(Map<String, String> cookies) {
        RequestSpecification spec = given().redirects().follow(false).cookies(cookies);
        String token = csrfToken(cookies);
        if (token != null) {
            spec.header("X-CSRF-TOKEN", token);
        }
        return spec;
    }

    private static String csrfToken(Map<String, String> cookies) {
        String token = cookies.get("csrftoken");
        if (token == null) {
            token = cookies.get("csrf-token");
        }
        return token;
    }

    private static String uniqueSuffix() {
        return UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    // ── Fixture helpers ──────────────────────────────────────────────────

    /**
     * Seeds one ComprobantesEmitidos through the production service with an
     * Emisor whose name embeds the consecutivo (unique per scenario).
     */
    private ComprobantesEmitidos seedRecibo(String consecutivo, LocalDateTime fechaEmision,
                                            String condicionVenta, String plazoCredito,
                                            String codigoDocumento, String estado,
                                            String haciendaEstado, BigDecimal total,
                                            BigDecimal impuesto) {
        Emisor emisor = new Emisor();
        emisor.setNombre("Proveedor " + consecutivo);

        Encabezado encabezado = new Encabezado();
        encabezado.setNumeroConsecutivo(consecutivo);
        encabezado.setFechaEmision(fechaEmision);
        encabezado.setCondicionVenta(condicionVenta);
        encabezado.setPlazoCredito(plazoCredito);
        encabezado.setCodigoDocumento(codigoDocumento);
        encabezado.setEstado(estado);
        encabezado.setEmisor(emisor);

        ResumenFactura resumen = new ResumenFactura();
        resumen.setTotalComprobante(total);
        resumen.setTotalImpuesto(impuesto);

        ComprobantesEmitidos recibo = new ComprobantesEmitidos();
        recibo.setEncabezado(encabezado);
        recibo.setResumen(resumen);
        recibo.setStatus(true);
        recibo.setHaciendaEstado(haciendaEstado);
        recibo.setUser("admin");
        return emitidosService.createAndReturn(recibo);
    }

    /** Fresh PENDIENTE factura electrónica (contado by default). */
    private ComprobantesEmitidos seedPendiente(String sufijo) {
        return seedRecibo("T27" + uniqueSuffix() + sufijo, LocalDateTime.now().minusDays(1),
                "01", "0", "01", "PENDIENTE", "PENDIENTE",
                new BigDecimal("10000"), new BigDecimal("1300"));
    }

    private void deleteQuietly(ComprobantesEmitidos... filas) {
        for (ComprobantesEmitidos f : filas) {
            if (f != null && f.getId() != null) {
                ComprobantesEmitidos managed = emitidosService.find(f.getId());
                if (managed != null) {
                    emitidosService.delete(managed);
                }
            }
        }
    }

    private boolean alertaEscrita(String source, String mensajeContiene) {
        List<Alertas> todas = alertas.listAll();
        return todas.stream()
                .anyMatch(a -> source.equals(a.getSource())
                        && a.getMensaje() != null
                        && a.getMensaje().contains(mensajeContiene));
    }

    private List<Map<String, Object>> filasDeBucket(Map<String, String> session, String bucket,
                                                    String q) {
        Response respuesta = authed(session)
                .queryParam("bucket", bucket)
                .queryParam("size", 500)
                .queryParam("q", q == null ? "" : q)
                .when().get(API);
        respuesta.then().statusCode(200);
        return respuesta.jsonPath().getList("data");
    }

    private boolean bucketContiene(Map<String, String> session, String bucket,
                                   String consecutivo) {
        String sufijo = consecutivo.length() > 8 ? consecutivo.substring(consecutivo.length() - 8) : consecutivo;
        List<Map<String, Object>> filas = filasDeBucket(session, bucket, sufijo);
        return filas.stream()
                .anyMatch(f -> consecutivo.equals(String.valueOf(f.get("consecutivo"))));
    }

    // ── 1. Todas bucket + DTO field parity ───────────────────────────────

    @Test
    void todasBucketListsSeededRowsWithDtoParity() {
        Map<String, String> session = adminSession();
        ComprobantesEmitidos fila = null;
        try {
            fila = seedRecibo("T27" + uniqueSuffix() + "A1", LocalDateTime.now().minusDays(2),
                    "02", "15", "01", "PENDIENTE", "ENVIADO",
                    new BigDecimal("25000.50"), new BigDecimal("3250.00"));

            Response respuesta = authed(session)
                    .queryParam("bucket", "todas")
                    .queryParam("q", fila.getEncabezado().getNumeroConsecutivo())
                    .when().get(API);
            respuesta.then().statusCode(200)
                    .body("total", equalTo(1))
                    .body("page", equalTo(1))
                    .body("size", equalTo(20));

            List<Map<String, Object>> data = respuesta.jsonPath().getList("data");
            assertThat(data).hasSize(1);
            Map<String, Object> dto = data.get(0);
            assertThat(String.valueOf(dto.get("consecutivo")))
                    .isEqualTo(fila.getEncabezado().getNumeroConsecutivo());
            assertThat(String.valueOf(dto.get("emisorNombre")))
                    .isEqualTo("Proveedor " + fila.getEncabezado().getNumeroConsecutivo());
            assertThat(new BigDecimal(String.valueOf(dto.get("totalComprobante"))))
                    .isEqualByComparingTo("25000.50");
            assertThat(new BigDecimal(String.valueOf(dto.get("totalImpuesto"))))
                    .isEqualByComparingTo("3250.00");
            assertThat(dto.get("codigoDocumento")).isEqualTo("01");
            assertThat(dto.get("condicionVenta")).isEqualTo("02");
            assertThat(dto.get("plazoCredito")).isEqualTo("15");
            assertThat(dto.get("haciendaEstado")).isEqualTo("ENVIADO");
            assertThat(dto.get("status")).isEqualTo(true);
        } finally {
            deleteQuietly(fila);
        }
    }

    // ── 2. Pagadas bucket: settled terminal state only ───────────────────

    @Test
    void pagadasBucketContainsOnlySettledRows() {
        Map<String, String> session = adminSession();
        ComprobantesEmitidos pagada = null;
        ComprobantesEmitidos pendiente = null;
        try {
            pagada = seedRecibo("T27" + uniqueSuffix() + "P1", LocalDateTime.now(),
                    "01", "0", "01", "ACEPTADO", "ACEPTADO",
                    BigDecimal.TEN, BigDecimal.ONE);
            pendiente = seedPendiente("Q1");

            String consecPagada = pagada.getEncabezado().getNumeroConsecutivo();
            String consecPendiente = pendiente.getEncabezado().getNumeroConsecutivo();

            assertThat(bucketContiene(session, "pagadas", consecPagada)).isTrue();
            assertThat(bucketContiene(session, "pagadas", consecPendiente)).isFalse();
        } finally {
            deleteQuietly(pagada, pendiente);
        }
    }

    // ── 3. Procesadas bucket: Hacienda exchange done ─────────────────────

    @Test
    void procesadasBucketFollowsHaciendaExchangeRule() {
        Map<String, String> session = adminSession();
        ComprobantesEmitidos enviado = null;
        ComprobantesEmitidos fresco = null;
        ComprobantesEmitidos pendienteHacienda = null;
        try {
            enviado = seedRecibo("T27" + uniqueSuffix() + "R1", LocalDateTime.now(),
                    "01", "0", "01", "PENDIENTE", "ENVIADO",
                    BigDecimal.TEN, BigDecimal.ONE);
            fresco = seedRecibo("T27" + uniqueSuffix() + "R2", LocalDateTime.now(),
                    "01", "0", "01", null, null,
                    BigDecimal.TEN, BigDecimal.ONE);
            pendienteHacienda = seedRecibo("T27" + uniqueSuffix() + "R3", LocalDateTime.now(),
                    "01", "0", "01", "PENDIENTE", "PENDIENTE",
                    BigDecimal.TEN, BigDecimal.ONE);

            assertThat(bucketContiene(session, "procesadas",
                    enviado.getEncabezado().getNumeroConsecutivo())).isTrue();
            assertThat(bucketContiene(session, "procesadas",
                    fresco.getEncabezado().getNumeroConsecutivo())).isFalse();
            assertThat(bucketContiene(session, "procesadas",
                    pendienteHacienda.getEncabezado().getNumeroConsecutivo())).isFalse();
        } finally {
            deleteQuietly(enviado, fresco, pendienteHacienda);
        }
    }

    // ── 4. Vencidos bucket: legacy overdue-credit rule ───────────────────

    @Test
    void vencidosBucketMirrorsLegacyOverdueCreditRule() {
        Map<String, String> session = adminSession();
        ComprobantesEmitidos vencidaReal = null;
        ComprobantesEmitidos futura = null;
        ComprobantesEmitidos contadoVieja = null;
        ComprobantesEmitidos saldadaVieja = null;
        ComprobantesEmitidos plazoIlegible = null;
        try {
            vencidaReal = seedRecibo("T27" + uniqueSuffix() + "V1", LocalDateTime.now().minusDays(40),
                    "02", "30", "01", "PENDIENTE", "PENDIENTE",
                    BigDecimal.TEN, BigDecimal.ONE);
            futura = seedRecibo("T27" + uniqueSuffix() + "V2", LocalDateTime.now().minusDays(5),
                    "02", "30", "01", "PENDIENTE", "PENDIENTE",
                    BigDecimal.TEN, BigDecimal.ONE);
            contadoVieja = seedRecibo("T27" + uniqueSuffix() + "V3", LocalDateTime.now().minusDays(40),
                    "01", "0", "01", "PENDIENTE", "PENDIENTE",
                    BigDecimal.TEN, BigDecimal.ONE);
            saldadaVieja = seedRecibo("T27" + uniqueSuffix() + "V4", LocalDateTime.now().minusDays(40),
                    "02", "30", "01", "ACEPTADO", "ACEPTADO",
                    BigDecimal.TEN, BigDecimal.ONE);
            plazoIlegible = seedRecibo("T27" + uniqueSuffix() + "V5", LocalDateTime.now().minusDays(40),
                    "02", "abc", "01", "PENDIENTE", "PENDIENTE",
                    BigDecimal.TEN, BigDecimal.ONE);

            assertThat(bucketContiene(session, "vencidos",
                    vencidaReal.getEncabezado().getNumeroConsecutivo()))
                    .as("credit sale past due and unsettled must be vencida")
                    .isTrue();
            assertThat(bucketContiene(session, "vencidos",
                    futura.getEncabezado().getNumeroConsecutivo()))
                    .as("credit sale inside its plazo must NOT be vencida")
                    .isFalse();
            assertThat(bucketContiene(session, "vencidos",
                    contadoVieja.getEncabezado().getNumeroConsecutivo()))
                    .as("contado sales can never vencer")
                    .isFalse();
            assertThat(bucketContiene(session, "vencidos",
                    saldadaVieja.getEncabezado().getNumeroConsecutivo()))
                    .as("settled receipts leave the vencidos bucket")
                    .isFalse();
            assertThat(bucketContiene(session, "vencidos",
                    plazoIlegible.getEncabezado().getNumeroConsecutivo()))
                    .as("unparseable plazoCredito mirrors listVencidas' NumberFormatException guard")
                    .isFalse();
        } finally {
            deleteQuietly(vencidaReal, futura, contadoVieja, saldadaVieja, plazoIlegible);
        }
    }

    // ── 5. Paging/sorting contract ───────────────────────────────────────

    @Test
    void listPagingAndSortingContract() {
        Map<String, String> session = adminSession();
        ComprobantesEmitidos alta = null;
        ComprobantesEmitidos media = null;
        ComprobantesEmitidos baja = null;
        try {
            String marca = uniqueSuffix();
            alta = seedRecibo("T27" + marca + "S1", LocalDateTime.now(), "01", "0", "01",
                    "PENDIENTE", "PENDIENTE", new BigDecimal("300"), BigDecimal.ONE);
            media = seedRecibo("T27" + marca + "S2", LocalDateTime.now(), "01", "0", "01",
                    "PENDIENTE", "PENDIENTE", new BigDecimal("200"), BigDecimal.ONE);
            baja = seedRecibo("T27" + marca + "S3", LocalDateTime.now(), "01", "0", "01",
                    "PENDIENTE", "PENDIENTE", new BigDecimal("100"), BigDecimal.ONE);

            Response pagina1 = authed(session)
                    .queryParam("bucket", "todas")
                    .queryParam("q", marca)
                    .queryParam("size", 2)
                    .queryParam("sort", "totalComprobante")
                    .queryParam("dir", "desc")
                    .when().get(API);
            pagina1.then().statusCode(200)
                    .body("total", equalTo(3))
                    .body("size", equalTo(2));
            assertThat(pagina1.jsonPath().getDouble("data[0].totalComprobante")).isEqualTo(300.0);
            assertThat(pagina1.jsonPath().getDouble("data[1].totalComprobante")).isEqualTo(200.0);

            Response pagina2 = authed(session)
                    .queryParam("bucket", "todas")
                    .queryParam("q", marca)
                    .queryParam("page", 2)
                    .queryParam("size", 2)
                    .queryParam("sort", "totalComprobante")
                    .queryParam("dir", "desc")
                    .when().get(API);
            pagina2.then().statusCode(200)
                    .body("page", equalTo(2));
            assertThat(pagina2.jsonPath().getDouble("data[0].totalComprobante")).isEqualTo(100.0);
        } finally {
            deleteQuietly(alta, media, baja);
        }
    }

    // ── 6. Global filter q (legacy globalFilterFunction fields) ──────────

    @Test
    void globalFilterQMatchesLegacyFields() {
        Map<String, String> session = adminSession();
        ComprobantesEmitidos fila = null;
        try {
            fila = seedPendiente("F1");
            String consecutivo = fila.getEncabezado().getNumeroConsecutivo();
            String emisor = fila.getEncabezado().getEmisor().getNombre();

            assertThat(bucketContiene(session, "todas", consecutivo)).isTrue();
            assertThat(bucketContiene(session, "todas", consecutivo.toLowerCase())).isTrue();

            List<Map<String, Object>> porEmisor = filasDeBucket(session, "todas", emisor);
            assertThat(porEmisor).extracting(f -> f.get("consecutivo")).contains(consecutivo);

            assertThat(filasDeBucket(session, "todas", "sin-coincidencia-" + uniqueSuffix()))
                    .isEmpty();
        } finally {
            deleteQuietly(fila);
        }
    }

    // ── 7. Stats consistency with the bucket lists ───────────────────────

    @Test
    void statsEndpointMatchesBucketTotals() {
        Map<String, String> session = adminSession();
        ComprobantesEmitidos fila = null;
        try {
            fila = seedPendiente("ST");

            Response stats = authed(session).when().get(API + "/stats");
            stats.then().statusCode(200)
                    .body("data.todas", org.hamcrest.Matchers.greaterThanOrEqualTo(1))
                    .body("data.pagadas", org.hamcrest.Matchers.greaterThanOrEqualTo(0));

            long todasStats = stats.jsonPath().getLong("data.todas");
            long pagadasStats = stats.jsonPath().getLong("data.pagadas");
            long procesadasStats = stats.jsonPath().getLong("data.procesadas");
            long vencidosStats = stats.jsonPath().getLong("data.vencidos");

            Response listaTodas = authed(session)
                    .queryParam("bucket", "todas").queryParam("size", 1).when().get(API);
            Response listaPagadas = authed(session)
                    .queryParam("bucket", "pagadas").queryParam("size", 1).when().get(API);
            Response listaProcesadas = authed(session)
                    .queryParam("bucket", "procesadas").queryParam("size", 1).when().get(API);
            Response listaVencidos = authed(session)
                    .queryParam("bucket", "vencidos").queryParam("size", 1).when().get(API);

            assertThat(todasStats).isEqualTo(listaTodas.jsonPath().getLong("total"));
            assertThat(pagadasStats).isEqualTo(listaPagadas.jsonPath().getLong("total"));
            assertThat(procesadasStats).isEqualTo(listaProcesadas.jsonPath().getLong("total"));
            assertThat(vencidosStats).isEqualTo(listaVencidos.jsonPath().getLong("total"));
        } finally {
            deleteQuietly(fila);
        }
    }

    // ── 8. Pay happy path: flip + audit alert + bucket move ──────────────

    @Test
    void payFlipsEstadoWritesAuditAlertAndMovesBucket() {
        Map<String, String> session = adminSession();
        ComprobantesEmitidos fila = null;
        try {
            fila = seedRecibo("T27" + uniqueSuffix() + "Y1", LocalDateTime.now().minusDays(40),
                    "02", "30", "01", "PENDIENTE", "PENDIENTE",
                    BigDecimal.TEN, BigDecimal.ONE);
            String consecutivo = fila.getEncabezado().getNumeroConsecutivo();

            assertThat(bucketContiene(session, "vencidos", consecutivo)).isTrue();

            authed(session)
                    .when().post(API + "/" + fila.getId() + "/pay")
                    .then()
                    .statusCode(200)
                    .body("data.success", equalTo(true))
                    .body("data.estado", equalTo("ACEPTADO"))
                    .body("data.mensaje", equalTo("Se marco la factura como pagada!"));

            ComprobantesEmitidos trasPago = emitidosService.find(fila.getId());
            assertNotNull(trasPago);
            assertThat(trasPago.getEncabezado().getEstado()).isEqualTo("ACEPTADO");

            assertThat(bucketContiene(session, "pagadas", consecutivo)).isTrue();
            assertThat(bucketContiene(session, "vencidos", consecutivo))
                    .as("paying moves the receipt out of vencidos")
                    .isFalse();

            assertThat(alertaEscrita("paySelectedFactura",
                    "Se marco la factura #" + fila.getId() + " como pagada"))
                    .as("legacy audit alert strings preserved verbatim")
                    .isTrue();
        } finally {
            deleteQuietly(fila);
        }
    }

    // ── 9. Pay guards: credit-note hide-pay regression (20d3cde) + others ─

    @Test
    void payEnforcesCreditNoteHidePayRuleAndGuards() {
        Map<String, String> session = adminSession();
        ComprobantesEmitidos notaCredito = null;
        ComprobantesEmitidos yaPagada = null;
        try {
            notaCredito = seedRecibo("T27" + uniqueSuffix() + "N1", LocalDateTime.now(),
                    "01", "0", "02", "PENDIENTE", "PENDIENTE",
                    BigDecimal.TEN, BigDecimal.ONE);
            yaPagada = seedRecibo("T27" + uniqueSuffix() + "N2", LocalDateTime.now(),
                    "01", "0", "01", "ACEPTADO", "ACEPTADO",
                    BigDecimal.TEN, BigDecimal.ONE);

            authed(session)
                    .when().post(API + "/" + notaCredito.getId() + "/pay")
                    .then()
                    .statusCode(409)
                    .body("error.code", equalTo("BUSINESS_RULE"))
                    .body("error.message", containsString("notas de crédito"));

            ComprobantesEmitidos ncTrasIntento = emitidosService.find(notaCredito.getId());
            assertThat(ncTrasIntento.getEncabezado().getEstado())
                    .as("commit 20d3cde: credit notes are never marked paid")
                    .isEqualTo("PENDIENTE");

            authed(session)
                    .when().post(API + "/" + yaPagada.getId() + "/pay")
                    .then()
                    .statusCode(409)
                    .body("error.message", equalTo("La factura ya fue pagada."));

            authed(session)
                    .when().post(API + "/999999999/pay")
                    .then()
                    .statusCode(404)
                    .body("error.code", equalTo("NOT_FOUND"));
        } finally {
            deleteQuietly(notaCredito, yaPagada);
        }
    }

    // ── 10. North-star alias POST /{id}/pagar ────────────────────────────

    @Test
    void pagarAliasFlipsTheSameStateAsPay() {
        Map<String, String> session = adminSession();
        ComprobantesEmitidos fila = null;
        try {
            fila = seedPendiente("G1");

            authed(session)
                    .when().post(API + "/" + fila.getId() + "/pagar")
                    .then()
                    .statusCode(200)
                    .body("data.success", equalTo(true))
                    .body("data.estado", equalTo("ACEPTADO"));

            ComprobantesEmitidos trasPago = emitidosService.find(fila.getId());
            assertThat(trasPago.getEncabezado().getEstado()).isEqualTo("ACEPTADO");
        } finally {
            deleteQuietly(fila);
        }
    }

    // ── 11. Process offline path: verbatim delegation, no mutation ───────

    @Test
    void processOfflinePathDelegatesWithoutMutating() {
        Map<String, String> session = adminSession();
        ComprobantesEmitidos fila = null;
        try {
            fila = seedRecibo("T27" + uniqueSuffix() + "M1", LocalDateTime.now(),
                    "01", "0", "01", "PENDIENTE", "PENDIENTE",
                    BigDecimal.TEN, BigDecimal.ONE);
            assertThat(fila.getHaciendaClave())
                    .as("fixture must hit the service's no-clave short circuit")
                    .isNull();

            authed(session)
                    .when().post(API + "/" + fila.getId() + "/process")
                    .then()
                    .statusCode(200)
                    .body("data.success", equalTo(false))
                    .body("data.haciendaEstado", equalTo("PENDIENTE"));

            ComprobantesEmitidos trasProceso = emitidosService.find(fila.getId());
            assertThat(trasProceso.getHaciendaEstado()).isEqualTo("PENDIENTE");
            assertThat(trasProceso.getEncabezado().getEstado()).isEqualTo("PENDIENTE");

            assertThat(alertaEscrita("ComprobanteService.enviarComprobanteAHacienda()",
                    "Comprobante sin clave de Hacienda"))
                    .as("the delegated service wrote its own audit alert")
                    .isTrue();
        } finally {
            deleteQuietly(fila);
        }
    }

    // ── 12. Process success through the stubbed Hacienda facade ──────────

    @Test
    void processSuccessPathFlipsToAceptadoThroughHaciendaService() {
        org.mockito.Mockito.when(haciendaServiceFacade.submitDocument(any()))
                .thenReturn(HaciendaServiceFacade.SubmitResult.accepted());
        if (appSettingsService.returnCurrent() == null) {
            appSettingsService.findOrCreateCurrent();
        }

        Map<String, String> session = adminSession();
        ComprobantesEmitidos fila = null;
        try {
            fila = seedRecibo("T27" + uniqueSuffix() + "M2", LocalDateTime.now(),
                    "01", "0", "01", "PENDIENTE", "PENDIENTE",
                    BigDecimal.TEN, BigDecimal.ONE);
            fila.setHaciendaClave("5062508250000010100010000000101"
                    + String.format("%019d", 900000 + fila.getId()));
            emitidosService.update(fila);

            authed(session)
                    .when().post(API + "/" + fila.getId() + "/process")
                    .then()
                    .statusCode(200)
                    .body("data.success", equalTo(true))
                    .body("data.haciendaEstado", equalTo("ACEPTADO"))
                    .body("data.estado", equalTo("ACEPTADO"));

            ComprobantesEmitidos trasProceso = emitidosService.find(fila.getId());
            assertThat(trasProceso.getHaciendaEstado()).isEqualTo("ACEPTADO");
            assertThat(trasProceso.getEncabezado().getEstado()).isEqualTo("ACEPTADO");
            assertThat(trasProceso.getHaciendaFechaEnvio()).isNotNull();
        } finally {
            deleteQuietly(fila);
        }
    }

    // ── 13. Accept on credit notes + reject moves bucket & records motivo ─

    @Test
    void acceptMarksCreditNotesAndRejectMovesBucketWithMotivo() {
        Map<String, String> session = adminSession();
        ComprobantesEmitidos notaCredito = null;
        ComprobantesEmitidos vencida = null;
        ComprobantesEmitidos rechazableJson = null;
        try {
            notaCredito = seedRecibo("T27" + uniqueSuffix() + "C1", LocalDateTime.now(),
                    "01", "0", "02", "PENDIENTE", "PENDIENTE",
                    BigDecimal.TEN, BigDecimal.ONE);

            authed(session)
                    .when().post(API + "/" + notaCredito.getId() + "/accept")
                    .then()
                    .statusCode(200)
                    .body("data.success", equalTo(true))
                    .body("data.estado", equalTo("ACEPTADO"));
            assertThat(emitidosService.find(notaCredito.getId()).getEncabezado().getEstado())
                    .as("unlike pay, accept works on credit notes too")
                    .isEqualTo("ACEPTADO");
            assertThat(alertaEscrita("RecibosResource.accept", "#" + notaCredito.getId())).isTrue();

            vencida = seedRecibo("T27" + uniqueSuffix() + "C2", LocalDateTime.now().minusDays(40),
                    "02", "30", "01", "PENDIENTE", "PENDIENTE",
                    BigDecimal.TEN, BigDecimal.ONE);
            String consecutivo = vencida.getEncabezado().getNumeroConsecutivo();
            assertThat(bucketContiene(session, "vencidos", consecutivo)).isTrue();

            authed(session)
                    .contentType(ContentType.URLENC)
                    .formParam("motivo", "Factura con productos dañados")
                    .when().post(API + "/" + vencida.getId() + "/reject")
                    .then()
                    .statusCode(200)
                    .body("data.success", equalTo(true))
                    .body("data.estado", equalTo("RECHAZADO"));

            ComprobantesEmitidos rechazada = emitidosService.find(vencida.getId());
            assertThat(rechazada.getEncabezado().getEstado()).isEqualTo("RECHAZADO");
            assertThat(rechazada.getEncabezado().getMotivoRechazo())
                    .isEqualTo("Factura con productos dañados");
            assertThat(bucketContiene(session, "vencidos", consecutivo))
                    .as("rejecting the receipt moves it out of its bucket")
                    .isFalse();
            assertThat(alertaEscrita("RecibosResource.reject", "#" + vencida.getId())).isTrue();

            rechazableJson = seedPendiente("C3");
            authed(session)
                    .contentType(ContentType.JSON)
                    .body(Map.of("motivo", "Rechazo por JSON"))
                    .when().post(API + "/" + rechazableJson.getId() + "/reject")
                    .then()
                    .statusCode(200)
                    .body("data.estado", equalTo("RECHAZADO"));

            authed(session)
                    .contentType(ContentType.URLENC)
                    .formParam("motivo", "x".repeat(501))
                    .when().post(API + "/" + rechazableJson.getId() + "/reject")
                    .then()
                    .statusCode(400)
                    .body("error.code", equalTo("VALIDATION_ERROR"));
        } finally {
            deleteQuietly(notaCredito, vencida, rechazableJson);
        }
    }

    // ── 14. Delete port: softDelete + verbatim audit strings ─────────────

    @Test
    void deletePortSoftDeletesWithVerbatimAuditStrings() {
        Map<String, String> session = adminSession();
        ComprobantesEmitidos fila = null;
        try {
            fila = seedPendiente("D1");

            authed(session)
                    .when().delete(API + "/" + fila.getId())
                    .then()
                    .statusCode(200)
                    .body("data.success", equalTo(true));

            ComprobantesEmitidos borrada = emitidosService.find(fila.getId());
            assertNotNull(borrada);
            assertThat(borrada.getStatus())
                    .as("softDelete flips status to false, row survives")
                    .isFalse();

            assertThat(alertaEscrita("ComprobantesEmitidosController.deleteFactura",
                    "La factura ha sido eliminada correctamente.")).isTrue();
        } finally {
            deleteQuietly(fila);
        }
    }

    // ── 15. Toggle port: status flip + verbatim audit strings ────────────

    @Test
    void togglePortFlipsStatusWithVerbatimAuditStrings() {
        Map<String, String> session = adminSession();
        ComprobantesEmitidos fila = null;
        try {
            fila = seedPendiente("T9");
            assertThat(fila.getStatus()).isTrue();

            authed(session)
                    .when().post(API + "/" + fila.getId() + "/toggle")
                    .then()
                    .statusCode(200)
                    .body("data.success", equalTo(true));
            assertThat(emitidosService.find(fila.getId()).getStatus()).isFalse();

            authed(session)
                    .when().post(API + "/" + fila.getId() + "/toggle")
                    .then()
                    .statusCode(200);
            assertThat(emitidosService.find(fila.getId()).getStatus()).isTrue();

            assertThat(alertaEscrita("ComprobantesEmitidosController.toggleFactura",
                    "El estado de la factura ha sido cambiado correctamente.")).isTrue();
        } finally {
            deleteQuietly(fila);
        }
    }

    // ── 16. Role matrix + unauthenticated challenge ──────────────────────

    @Test
    @TestSecurity(user = "bodeguero", roles = {"inventario"})
    void nonFacturacionRoleIsForbiddenEverywhere() {
        given().when().get(API).then().statusCode(403);
        given().when().get(API + "/stats").then().statusCode(403);
        given().when().get(API + "/tabla").then().statusCode(403);
        given().when().post(API + "/1/pay").then().statusCode(403);
        given().when().delete(API + "/1").then().statusCode(403);
        given().when().get(PAGE).then().statusCode(403);
    }

    @Test
    void unauthenticatedRequestsAreChallenged() {
        given().redirects().follow(false)
                .when().get(API)
                .then()
                .statusCode(302)
                .header("Location", containsString("/login"));
        given().redirects().follow(false)
                .when().post(API + "/1/pay")
                .then()
                .statusCode(302);
        given().redirects().follow(false)
                .when().get(PAGE)
                .then()
                .statusCode(302);
    }

    // ── 17. Page render markers + fragment dual-mode + detail panel ──────

    @Test
    void pageRendersBoardMarkersAndTablaFragmentDualMode() {
        Map<String, String> session = adminSession();

        authed(session)
                .when().get(PAGE)
                .then()
                .statusCode(200)
                .contentType(containsString("text/html"))
                .body(containsString("Gestión de Recibos"))
                .body(containsString("id=\"recibos-tablero\""))
                .body(containsString("id=\"recibos-stats\""))
                .body(containsString("id=\"recibos-tabla-todas\""))
                .body(containsString("id=\"recibos-tabla-pagadas\""))
                .body(containsString("id=\"recibos-tabla-procesadas\""))
                .body(containsString("id=\"recibos-tabla-vencidos\""))
                .body(containsString("Detalle del recibo"))
                .body(containsString("id=\"recibo-detalle-cuerpo\""));

        authed(session)
                .header("HX-Request", "true")
                .queryParam("bucket", "vencidos")
                .when().get(API + "/tabla")
                .then()
                .statusCode(200)
                .contentType(containsString("text/html"))
                .body(containsString("id=\"recibos-tabla-vencidos\""))
                .body(containsString("Vencidos"));

        String fragmento = authed(session)
                .header("HX-Request", "true")
                .queryParam("bucket", "todas")
                .when().get(API + "/tabla").asString();
        assertThat(fragmento).doesNotContain("id=\"recibos-tabla-pagadas\"");
        assertThat(fragmento).doesNotContain("<footer");

        authed(session)
                .when().get(API + "/tabla")
                .then()
                .statusCode(200)
                .body(containsString("id=\"recibos-tablero\""))
                .body(containsString("id=\"recibos-stats\""));
    }

    @Test
    void detailPanelFragmentAndUnknownIdEnvelope() {
        Map<String, String> session = adminSession();
        ComprobantesEmitidos fila = null;
        try {
            fila = seedPendiente("DP");

            authed(session)
                    .header("HX-Request", "true")
                    .when().get(API + "/" + fila.getId())
                    .then()
                    .statusCode(200)
                    .contentType(containsString("text/html"))
                    .body(containsString("id=\"recibo-detalle-cuerpo\""))
                    .body(containsString("Refrescar"))
                    .body(containsString("Total Comprobante"))
                    .body(containsString("Marcar como pagada"));

            authed(session)
                    .when().get(API + "/" + fila.getId())
                    .then()
                    .statusCode(200)
                    .contentType(ContentType.JSON)
                    .body("data.consecutivo", equalTo(fila.getEncabezado().getNumeroConsecutivo()))
                    .body("data.emisorNombre", equalTo("Proveedor " + fila.getEncabezado().getNumeroConsecutivo()));

            authed(session)
                    .when().get(API + "/999999999")
                    .then()
                    .statusCode(404)
                    .body("error.code", equalTo("NOT_FOUND"));
        } finally {
            deleteQuietly(fila);
        }
    }
}
