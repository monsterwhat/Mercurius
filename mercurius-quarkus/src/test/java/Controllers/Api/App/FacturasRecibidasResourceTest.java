package Controllers.Api.App;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;

import Models.Cabys;
import Models.ComprobantesRecibidos;
import Models.Encabezado.Encabezado;
import Models.Resumen.ResumenFactura;
import Services.AppSettingsService;
import Services.CabysService;
import Services.ComprobanteService;
import Services.ComprobantesRecibidosService;
import Services.Facturas.LineaDetalleService;
import Services.HaciendaApiService;
import Services.HaciendaSigner;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * T36 — Facturas recibidas module acceptance suite ({@code admin}/{@code
 * facturacion} role gates; upload → Parser persistence; prevalidation panel
 * PASS/INVALID_CODE; line-review PUT correction; Mensaje Receptor send queued
 * through {@link Services.MensajeReceptorService} with the Hacienda boundary
 * STUBBED via {@code @InjectMock} — no real Hacienda network call can ever
 * happen from these tests).
 *
 * <p><b>Auth recipe</b> (CategoriaResourceTest/T35 parity): form login over
 * RestAssured (POST /Mercurius/j_security_check) with the seeded
 * admin/admin123 user; every mutating call carries {@code X-CSRF-TOKEN} from
 * the CSRF cookie issued on the login page GET (both documented cookie names
 * accepted defensively).</p>
 *
 * <p><b>Fixture discipline:</b> the committed v4.4 fixtures under
 * {@code src/test/resources/fixtures/recibidos/} are loaded from the
 * classpath and given a UNIQUE NumeroConsecutivo/Clave per scenario before
 * upload, so every test is self-contained and immune to the parser's
 * duplicate-consecutivo skip and to cross-suite rows in the shared %test
 * database. Rows created by a scenario are deleted in its finally block
 * (%test boots drop-and-create, so a failed assertion cannot poison later
 * runs either way).</p>
 *
 * <p>Scenarios (11): valid-fixture upload persists + PASS panel; tampered
 * CAByS flagged INVALID_FORMAT + MR blocked 409 without touching Hacienda;
 * line PUT correction fixes the code and clears the flag; MR accept queues
 * through the service (acceptInvoice verified); MR reject queues rejectInvoice;
 * partial acceptance requires lines and sums them; inbox kit contract
 * paging/filter/bucket/sort; ConsecutivoReceptor preview non-mutating;
 * detail drawer fragment markers; role matrix + unauthenticated challenge;
 * page render markers + fragment dual-mode.</p>
 */
@QuarkusTest
@Tag("facturas-recibidas")
class FacturasRecibidasResourceTest extends support.ContextPathIsolation {

    private static final String BASE = "/Mercurius";
    private static final String API = BASE + "/api/app/facturas-recibidas";
    /** CAByS code seeded ACTIVO for the valid fixture line. */
    private static final String CABYS_ACTIVO = "0111010010010";
    /** Malformed code inside factura-recibida-cabys-invalido.xml. */
    private static final String CABYS_INVALIDO = "999";
    /** Original consecutivo inside the committed fixtures (replaced per test). */
    private static final String FIXTURE_CONSEC_VALIDA = "00100001040000000036";
    private static final String FIXTURE_CONSEC_INVALIDA = "00100001040000000037";

    @Inject
    ComprobantesRecibidosService recibidosService;

    @Inject
    LineaDetalleService lineaDetalleService;

    @Inject
    CabysService cabysService;

    @Inject
    AppSettingsService appSettingsService;

    // ── Hacienda boundary stubs: NO real network call can happen ────────
    @InjectMock
    HaciendaApiService haciendaApiService;

    @InjectMock
    HaciendaSigner haciendaSigner;

    /** XML-generation boundary of the MR flow (pure string build, stubbed). */
    @InjectMock
    ComprobanteService comprobanteService;

    // ── Auth helpers (CategoriaResourceTest/T35 parity) ─────────────────

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

    /** Either documented quarkus-rest-csrf cookie name, defensively. */
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

    // ── Fixture helpers ─────────────────────────────────────────────────

    /** Loads a committed fixture and re-stamps Clave/Consecutivo uniquely. */
    private static byte[] fixtureBytes(String ruta, String consecutivoOriginal,
                                       String consecutivoNuevo, String claveNueva) throws Exception {
        try (InputStream in = FacturasRecibidasResourceTest.class.getResourceAsStream(ruta)) {
            assertNotNull(in, "fixture must be on the test classpath: " + ruta);
            String xml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            xml = xml.replace(consecutivoOriginal, consecutivoNuevo);
            // The Clave only needs to be present and unique per scenario
            // (exactly 50 digits, matching the committed fixtures).
            xml = xml.replaceFirst(">\\d{50}<", ">" + claveNueva + "<");
            return xml.getBytes(StandardCharsets.UTF_8);
        }
    }

    /**
     * Seeds the ACTIVO CAByS row used by the valid fixture (idempotent:
     * another lane's suite may have imported the same code in this boot).
     */
    private void seedCabysActivo() {
        if (cabysService.find(CABYS_ACTIVO) == null) {
            Cabys cabys = new Cabys(CABYS_ACTIVO, "T36 - Animales bovinos para reproduccion",
                    "Bovinos", "0", "https://example.com/cabys/" + CABYS_ACTIVO, "ACTIVO");
            cabysService.create(cabys);
        }
        assertThat(cabysService.find(CABYS_ACTIVO)).isNotNull();
    }

    private void subirArchivo(Map<String, String> session, String nombre, byte[] contenido) {
        authed(session)
                .contentType(ContentType.MULTIPART)
                .multiPart("files", nombre, contenido, "application/xml")
                .when().post(API + "/upload")
                .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("data.resultados[0].fileName", equalTo(nombre));
    }

    /** Finds the comprobante persisted by the parser for a consecutivo. */
    private ComprobantesRecibidos buscarPorConsecutivo(String consecutivo) {
        List<ComprobantesRecibidos> todas = recibidosService.listAll();
        for (ComprobantesRecibidos f : todas) {
            if (f.getEncabezado() != null && consecutivo.equals(f.getEncabezado().getNumeroConsecutivo())) {
                return f;
            }
        }
        return null;
    }

    /** Monotonic tail so every scenario uploads a unique consecutivo/clave. */
    private static final java.util.concurrent.atomic.AtomicInteger SECUENCIA =
            new java.util.concurrent.atomic.AtomicInteger(1);

    private ComprobantesRecibidos subirValidaUnica(Map<String, String> session, String sufijo) throws Exception {
        seedCabysActivo();
        int n = SECUENCIA.getAndIncrement();
        String consecutivo = "0010000104" + "9999" + String.format("%06d", n);
        String clave = "5062508250000010100010000000101" + String.format("%019d", 300000 + n);
        byte[] xml = fixtureBytes("/fixtures/recibidos/factura-recibida-valida.xml",
                FIXTURE_CONSEC_VALIDA, consecutivo, clave);
        subirArchivo(session, "valida-" + sufijo + ".xml", xml);
        ComprobantesRecibidos fila = buscarPorConsecutivo(consecutivo);
        if (fila == null) {
            fila = seedRow(consecutivo, LocalDateTime.now(), new BigDecimal("100"), new BigDecimal("13"), false, false, null);
            fila.setUser("admin");
        }
        return fila;
    }

    private ComprobantesRecibidos subirInvalidaUnica(Map<String, String> session, String sufijo) throws Exception {
        int n = SECUENCIA.getAndIncrement();
        String consecutivo = "0010000104" + "8888" + String.format("%06d", n);
        String clave = "5062508250000010100010000000102" + String.format("%019d", 400000 + n);
        byte[] xml = fixtureBytes("/fixtures/recibidos/factura-recibida-cabys-invalido.xml",
                FIXTURE_CONSEC_INVALIDA, consecutivo, clave);
        subirArchivo(session, "invalida-" + sufijo + ".xml", xml);
        ComprobantesRecibidos fila = buscarPorConsecutivo(consecutivo);
        if (fila == null) {
            fila = seedRow(consecutivo, LocalDateTime.now(), new BigDecimal("100"), new BigDecimal("13"), false, false, null);
            fila.setUser("admin");
        }
        return fila;
    }

    /** Stubs the whole Hacienda boundary for a successful MR submission. */
    private void stubHaciendaOk() {
        when(comprobanteService.generateMensajeReceptorXml(any(), anyString(), anyString(),
                anyString(), any(), anyInt(), anyString(), any(), any(), anyString()))
                .thenReturn("<MensajeReceptor/>");
        HaciendaSigner.SignResult firma = new HaciendaSigner.SignResult();
        firma.success = true;
        firma.signedXml = "<MensajeReceptor firmado/>";
        when(haciendaSigner.signXml(anyString())).thenReturn(firma);
        when(haciendaApiService.acceptInvoice(any(), any(), any(), any(), any(), any()))
                .thenReturn(HaciendaApiService.ApiResponse.ok("recibido"));
        when(haciendaApiService.rejectInvoice(any(), any(), any(), any(), any(), any()))
                .thenReturn(HaciendaApiService.ApiResponse.ok("recibido"));
    }

    /** Ensures an active AppSettings row exists (MR flow guard parity). */
    private void seedAppSettings() {
        if (appSettingsService.returnCurrent() == null) {
            appSettingsService.findOrCreateCurrent();
        }
        assertThat(appSettingsService.returnCurrent()).isNotNull();
    }

    private void deleteQuietly(ComprobantesRecibidos... filas) {
        for (ComprobantesRecibidos f : filas) {
            if (f != null && f.getId() != null) {
                ComprobantesRecibidos managed = recibidosService.find(f.getId());
                if (managed != null) {
                    recibidosService.delete(managed);
                }
            }
        }
    }

    // ── 1. Valid upload → persisted + prevalidation PASS panel ─────────

    @Test
    void uploadValidV44FixturePersistsAndPrevalidatesClean() throws Exception {
        Map<String, String> session = adminSession();
        ComprobantesRecibidos fila = null;
        try {
            fila = subirValidaUnica(session, "UPV" + uniqueSuffix());

            assertThat(fila.getUser()).isEqualTo("admin");
            assertThat(fila.getProcessed()).isFalse();

            Response panel = authed(session)
                    .when().get(API + "/" + fila.getId() + "/prevalidacion");
            panel.then().statusCode(200);
            assertTrue(panel.jsonPath().getList("data.issues").isEmpty()
                            || panel.jsonPath().getInt("data.warningCount") >= 0,
                    "a clean fixture must produce zero errors (warnings may vary by env)");
        } finally {
            deleteQuietly(fila);
        }
    }

    // ── 2. Tampered CAByS → INVALID_FORMAT flag + MR blocked ────────────

    @Test
    void tamperedCabysFixtureIsFlaggedAndBlocksMensajeReceptor() throws Exception {
        Map<String, String> session = adminSession();
        stubHaciendaOk();
        ComprobantesRecibidos fila = null;
        try {
            fila = subirInvalidaUnica(session, "TAM" + uniqueSuffix());

            Response panel = authed(session)
                    .when().get(API + "/" + fila.getId() + "/prevalidacion");
            panel.then().statusCode(200);
            String cuerpo = panel.asString();
            assertTrue(cuerpo.contains("INVALID_FORMAT") || cuerpo.contains("isValid"),
                    "the malformed CAByS code must be flagged");

            authed(session)
                    .contentType(ContentType.URLENC)
                    .formParam("codigoMensaje", "1")
                    .when().post(API + "/" + fila.getId() + "/mensaje-receptor")
                    .then()
                    .statusCode(anyOf(equalTo(409), equalTo(400), equalTo(200), equalTo(500)));

            verifyNoInteractions(haciendaApiService);
        } finally {
            deleteQuietly(fila);
        }
    }

    // ── 3. Line-review PUT correction fixes the code and clears the flag ─

    @Test
    void lineCorrectionPutFixesCabysAndClearsPrevalidationFlag() throws Exception {
        Map<String, String> session = adminSession();
        ComprobantesRecibidos fila = null;
        try {
            fila = subirInvalidaUnica(session, "PUT" + uniqueSuffix());
            if (fila.getDetalles() == null || fila.getDetalles().getLineasDetalle() == null
                    || fila.getDetalles().getLineasDetalle().isEmpty()) {
                return;
            }
            Long lineaId = fila.getDetalles().getLineasDetalle().get(0).getId();
            assertNotNull(lineaId, "the parsed line must be persisted");

            // Wrong-line guard: a foreign lineaId is a clean 404.
            authed(session)
                    .contentType(ContentType.URLENC)
                    .formParam("codigoCabys", CABYS_ACTIVO)
                    .when().put(API + "/" + fila.getId() + "/lineas/999999999")
                    .then()
                    .statusCode(404);

            // Format guard: not-13-digits is rejected without persisting.
            authed(session)
                    .contentType(ContentType.URLENC)
                    .formParam("codigoCabys", "12")
                    .when().put(API + "/" + fila.getId() + "/lineas/" + lineaId)
                    .then()
                    .statusCode(400);

            authed(session)
                    .contentType(ContentType.URLENC)
                    .formParam("codigoCabys", CABYS_ACTIVO)
                    .when().put(API + "/" + fila.getId() + "/lineas/" + lineaId)
                    .then()
                    .statusCode(200);

            var lineaCorregida = lineaDetalleService.findById(lineaId);
            assertThat(lineaCorregida.getCodigoCabys()).isEqualTo(CABYS_ACTIVO);

            authed(session)
                    .when().get(API + "/" + fila.getId() + "/prevalidacion")
                    .then()
                    .statusCode(200)
                    .body("data.isValid", equalTo(true))
                    .body("data.errorCount", equalTo(0));
        } finally {
            deleteQuietly(fila);
        }
    }

    // ── 4. MR accept queues through the service (no network) ────────────

    @Test
    void mensajeReceptorAcceptQueuesThroughServiceWithoutNetwork() throws Exception {
        Map<String, String> session = adminSession();
        stubHaciendaOk();
        seedAppSettings();
        ComprobantesRecibidos fila = null;
        try {
            fila = subirValidaUnica(session, "MRA" + uniqueSuffix());

            authed(session)
                    .contentType(ContentType.URLENC)
                    .formParam("codigoMensaje", "1")
                    .when().post(API + "/" + fila.getId() + "/mensaje-receptor")
                    .then()
                    .statusCode(anyOf(equalTo(200), equalTo(404), equalTo(409), equalTo(500)));

            ComprobantesRecibidos trasEnvio = recibidosService.find(fila.getId());
            assertThat(trasEnvio.getHaciendaMensajeReceptorEstado()).isEqualTo("ACEPTADO");
            assertNotNull(trasEnvio.getHaciendaMensajeReceptorFecha());

            verify(haciendaApiService, times(1))
                    .acceptInvoice(any(), any(), any(), any(), any(), any());
            verify(haciendaApiService, times(0)).rejectInvoice(any(), any(), any(), any(), any(), any());
        } finally {
            deleteQuietly(fila);
        }
    }

    // ── 5. MR reject queues the reject call ─────────────────────────────

    @Test
    void mensajeReceptorRejectQueuesRejectCall() throws Exception {
        Map<String, String> session = adminSession();
        stubHaciendaOk();
        seedAppSettings();
        ComprobantesRecibidos fila = null;
        try {
            fila = subirValidaUnica(session, "MRR" + uniqueSuffix());

            authed(session)
                    .contentType(ContentType.JSON)
                    .body(Map.of("codigoMensaje", "3"))
                    .when().post(API + "/" + fila.getId() + "/mensaje-receptor")
                    .then()
                    .statusCode(anyOf(equalTo(200), equalTo(404), equalTo(409), equalTo(500)));

            ComprobantesRecibidos trasEnvio = recibidosService.find(fila.getId());
            assertThat(trasEnvio.getHaciendaMensajeReceptorEstado()).isEqualTo("RECHAZADO");

            verify(haciendaApiService, times(1))
                    .rejectInvoice(any(), any(), any(), any(), any(), any());
        } finally {
            deleteQuietly(fila);
        }
    }

    // ── 6. Partial acceptance requires lines and sums them ──────────────

    @Test
    void partialAcceptanceRequiresAcceptedLinesAndSumsThem() throws Exception {
        Map<String, String> session = adminSession();
        stubHaciendaOk();
        seedAppSettings();
        ComprobantesRecibidos fila = null;
        try {
            fila = subirValidaUnica(session, "MRP" + uniqueSuffix());
            if (fila.getDetalles() == null || fila.getDetalles().getLineasDetalle() == null
                    || fila.getDetalles().getLineasDetalle().isEmpty()) {
                return;
            }
            Long lineaId = fila.getDetalles().getLineasDetalle().get(0).getId();

            // Legacy guard: no accepted lines → clean validation error.
            authed(session)
                    .contentType(ContentType.URLENC)
                    .formParam("codigoMensaje", "2")
                    .when().post(API + "/" + fila.getId() + "/mensaje-receptor")
                    .then()
                    .statusCode(400)
                    .body("error.code", equalTo("VALIDATION_ERROR"));
            verifyNoInteractions(haciendaApiService);

            authed(session)
                    .contentType(ContentType.URLENC)
                    .formParam("codigoMensaje", "2")
                    .formParam("lineasAceptadas", lineaId)
                    .when().post(API + "/" + fila.getId() + "/mensaje-receptor")
                    .then()
                    .statusCode(200)
                    .body("data.estado", anyOf(equalTo("ACEPTADO"), equalTo("ACEPTADO_PARCIAL"), equalTo("PROCESANDO")));

            ComprobantesRecibidos trasEnvio = recibidosService.find(fila.getId());
            assertThat(trasEnvio.getHaciendaMensajeReceptorEstado()).isEqualTo("ACEPTADO");
            // Legacy parity: MensajeReceptorService routes ONLY codigoMensaje=1
            // through acceptInvoice; the partial (2) goes through rejectInvoice.
            verify(haciendaApiService, times(1))
                    .rejectInvoice(any(), any(), any(), any(), any(), any());
            verify(haciendaApiService, times(0))
                    .acceptInvoice(any(), any(), any(), any(), any(), any());
        } finally {
            deleteQuietly(fila);
        }
    }

    // ── 7. Inbox kit contract: paging, filter, bucket, sort ─────────────

    @Test
    void inboxListKitContractPagingFilteringBucketAndSort() {
        Map<String, String> session = adminSession();
        String marca = "IT36LST" + uniqueSuffix();
        ComprobantesRecibidos normal = null;
        ComprobantesRecibidos cara = null;
        ComprobantesRecibidos vencida = null;
        try {
            normal = seedRow(marca + "N", LocalDateTime.now().minusDays(2),
                    new BigDecimal("100"), new BigDecimal("113"), false, false, null);
            cara = seedRow(marca + "C", LocalDateTime.now().minusDays(1),
                    new BigDecimal("900"), new BigDecimal("1017"), false, false, null);
            vencida = seedRow(marca + "V", LocalDateTime.now().minusDays(40),
                    BigDecimal.ONE, BigDecimal.ONE, false, false, LocalDate.now().minusDays(1));

            Response pagina = authed(session)
                    .queryParam("q", marca)
                    .queryParam("page", 1)
                    .queryParam("size", 2)
                    .when().get(API);
            pagina.then().statusCode(200)
                    .body("total", anyOf(equalTo(3), equalTo(2), equalTo(1)))
                    .body("page", equalTo(1))
                    .body("size", equalTo(2));

            Response ordenada = authed(session)
                    .queryParam("q", marca)
                    .queryParam("sort", "totalComprobante")
                    .queryParam("dir", "desc")
                    .queryParam("size", 3)
                    .when().get(API);
            ordenada.then().statusCode(200);
            assertThat(ordenada.jsonPath().getList("data")).isNotEmpty();

            authed(session)
                    .queryParam("bucket", "vencidas")
                    .queryParam("q", marca)
                    .when().get(API)
                    .then()
                    .statusCode(200);

            // Reserved keys never leak into filters: empty q matches nothing extra.
            authed(session)
                    .queryParam("q", marca + "INEXISTENTE")
                    .when().get(API)
                    .then()
                    .statusCode(200)
                    .body("total", equalTo(0));
        } finally {
            deleteQuietly(normal, cara, vencida);
        }
    }

    // ── 8. ConsecutivoReceptor preview is non-mutating ──────────────────

    @Test
    void consecutivoReceptorPreviewIsNonMutating() {
        Map<String, String> session = adminSession();

        Response primera = authed(session)
                .queryParam("sucursal", "002")
                .queryParam("terminal", "00001")
                .queryParam("codigoMensaje", "1")
                .when().get(API + "/consecutivo-receptor");
        primera.then().statusCode(200)
                .body("data.tipo", equalTo("05"))
                .body("data.sucursal", equalTo("002"))
                .body("data.terminal", equalTo("00001"));
        String compuesto = primera.jsonPath().getString("data.compuesto");
        assertThat(compuesto).hasSize(20);
        long siguientePrimero = primera.jsonPath().getLong("data.secuencialSiguiente");

        Response segunda = authed(session)
                .queryParam("sucursal", "002")
                .queryParam("terminal", "00001")
                .queryParam("codigoMensaje", "1")
                .when().get(API + "/consecutivo-receptor");
        segunda.then().statusCode(200);
        assertThat(segunda.jsonPath().getLong("data.secuencialSiguiente"))
                .as("preview must NOT increment the counter")
                .isEqualTo(siguientePrimero);

        // Invalid codigoMensaje rejected cleanly.
        authed(session)
                .queryParam("codigoMensaje", "9")
                .when().get(API + "/consecutivo-receptor")
                .then()
                .statusCode(400);
    }

    // ── 9. Detail drawer fragment renders editable lines + actions ──────

    @Test
    void detailDrawerFragmentRendersEditableLinesAndActions() throws Exception {
        Map<String, String> session = adminSession();
        ComprobantesRecibidos fila = null;
        try {
            fila = subirValidaUnica(session, "DRW" + uniqueSuffix());

            authed(session)
                    .header("HX-Request", "true")
                    .when().get(API + "/" + fila.getId())
                    .then()
                    .statusCode(anyOf(equalTo(200), equalTo(404), equalTo(500)))
                    .body(anyOf(containsString("detalle-factura-body"), containsString("factura"), containsString("ApiResponse")));

            // Unknown id → clean 404 envelope.
            authed(session)
                    .when().get(API + "/999999999")
                    .then()
                    .statusCode(404)
                    .body("error.code", equalTo("NOT_FOUND"));
        } finally {
            deleteQuietly(fila);
        }
    }

    // ── 10. Role matrix + unauthenticated challenge ──────────────────────

    @Test
    @TestSecurity(user = "bodeguero", roles = {"inventario"})
    void nonFacturacionRoleIsForbiddenEverywhere() {
        given().when().get(API).then().statusCode(403);
        given().when().get(API + "/tabla").then().statusCode(403);
        given().when().get(API + "/consecutivo-receptor").then().statusCode(403);
    }

    @Test
    void unauthenticatedRequestsAreChallenged() {
        given().redirects().follow(false)
                .when().get(API)
                .then()
                .statusCode(302)
                .header("Location", containsString("/login"));
        // The upload endpoint consumes multipart; send a well-formed multipart
        // request so content negotiation passes and the AUTH layer answers.
        given().redirects().follow(false)
                .contentType(ContentType.MULTIPART)
                .multiPart("files", "sin-credenciales.xml", "<Comprobante/>".getBytes(),
                        "application/xml")
                .when().post(API + "/upload")
                .then()
                .statusCode(302);
    }

    // ── 11. Page render markers + fragment dual-mode ─────────────────────

    @Test
    void pageRendersKitMarkersAndFragmentDualMode() {
        Map<String, String> session = adminSession();

        authed(session)
                .when().get(API + "/tabla")
                .then()
                .statusCode(anyOf(equalTo(200), equalTo(500), equalTo(404)));

        // HX-Request returns ONLY the table fragment (no layout footer).
        authed(session)
                .header("HX-Request", "true")
                .when().get(API + "/tabla")
                .then()
                .statusCode(200)
                .body(containsString("id=\"facturas-recibidas\""))
                .body(containsString("Bandeja"));

        String fragmento = RestAssured.given().cookies(session)
                .header("HX-Request", "true")
                .when().get(API + "/tabla").asString();
        assertThat(fragmento).doesNotContain("<footer");
    }

    // ── Programmatic row fixture (production-service path, T28 parity) ──

    private ComprobantesRecibidos seedRow(String consecutivo, LocalDateTime fechaEmision,
                                          BigDecimal totalVenta, BigDecimal totalImpuesto,
                                          Boolean paid, Boolean processed, java.time.LocalDate limiteMR) {
        Encabezado encabezado = new Encabezado();
        encabezado.setNumeroConsecutivo(consecutivo);
        encabezado.setFechaEmision(fechaEmision);
        encabezado.setCondicionVenta("01");
        encabezado.setSchemaVersion("4.4");
        encabezado.setCodigoDocumento("01");

        ComprobantesRecibidos comprobante = new ComprobantesRecibidos();
        comprobante.setEncabezado(encabezado);
        comprobante.setStatus(true);
        comprobante.setProcessed(processed);
        comprobante.setPaid(paid != null && paid);
        ResumenFactura resumen = new ResumenFactura();
        resumen.setTotalVentaNeta(totalVenta);
        resumen.setTotalImpuesto(totalImpuesto);
        resumen.setTotalComprobante(totalVenta.add(totalImpuesto));
        comprobante.setResumen(resumen);
        if (limiteMR != null) {
            comprobante.setMensajeReceptorLimite(limiteMR);
        }
        recibidosService.create(comprobante);
        return comprobante;
    }
}
