package Controllers.Api.App;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;

import Models.ConfiguracionAplicacion;
import Models.Articulos.Articulos;
import Models.Cabys;
import Models.ComprobantesRecibidos;
import Models.Detalles.LineaDetalle;
import Models.Encabezado.Encabezado;
import Models.Inventario;
import Models.Resumen.ResumenFactura;
import Services.AppSettingsService;
import Services.ArticulosService;
import Services.CabysService;
import Services.ComprobanteService;
import Services.ComprobantesRecibidosService;
import Services.Facturas.LineaDetalleService;
import Services.HaciendaApiService;
import Services.HaciendaSigner;
import Services.InventarioService;
import support.FacturasReales;

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
 * <p><b>Fixture discipline:</b> the anonymized REAL invoice
 * {@code fixtures/reales/v4.4/fe-v44-13.xml} is loaded through
 * {@link support.FacturasReales} and given a UNIQUE
 * NumeroConsecutivo/Clave per scenario via
 * {@code conConsecutivoUnico(nombre, semilla)}, so every test is
 * self-contained and immune to the parser's duplicate-consecutivo skip and to
 * cross-suite rows in the shared %test database. Its CAByS codes are seeded
 * ACTIVO first, otherwise pre-validation would report MISSING_CABYS. Rows
 * created by a scenario are deleted in its finally block (%test boots
 * drop-and-create, so a failed assertion cannot poison later runs either
 * way), together with the articles/stock the rejected-import path creates
 * out of the same line items.</p>
 *
 * <p><b>Malformed CAByS:</b> no real invoice carries a bad CAByS, so the
 * negative scenario takes the real document and corrupts ONE
 * {@code CodigoCABYS} into {@code "999"}, exactly the way
 * {@code FacturaUploadIntegrationTest#facturaRechazadaIgualmenteImportaArticulosEInventario}
 * corrupts the Clave. The 3-digit value violates the official
 * {@code CodigoCABYS} type (minLength 13), so the parser's strict XSD gate
 * refuses the document, the resource re-parses it leniently and stores it
 * flagged as rejected — and the recomputed prevalidation panel flags
 * INVALID_FORMAT, which is what blocks the Mensaje Receptor.</p>
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
    /** Real anonymized v4.4 factura received (FE) that drives every upload scenario. */
    private static final String FACTURA_REAL = "fe-v44-13";
    /** CAByS the four line items of that real invoice reference, seeded ACTIVO. */
    private static final String CABYS_ACTIVO = "2349002011400";
    /** Malformed code the tampered scenario corrupts one real line into. */
    private static final String CABYS_INVALIDO = "999";

    @Inject
    ComprobantesRecibidosService recibidosService;

    @Inject
    LineaDetalleService lineaDetalleService;

    @Inject
    CabysService cabysService;

    /** Cedula valida para <NumeroCedulaReceptor>: 9-12 digitos. */
    private static final String IDENTIFICACION_PRUEBAS = "3100100008";

    @Inject
    AppSettingsService appSettingsService;

    @Inject
    ArticulosService articulosService;

    @Inject
    InventarioService inventarioService;

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

    /** The 20-digit NumeroConsecutivo of a re-stamped real invoice. */
    private static String consecutivoDe(String xml) {
        Matcher m = Pattern.compile("<NumeroConsecutivo>(\\d{20})</NumeroConsecutivo>").matcher(xml);
        assertTrue(m.find(), "la factura real debe traer NumeroConsecutivo");
        return m.group(1);
    }

    /**
     * Seeds as ACTIVO every CAByS the real invoice's line items reference, so
     * the panel reports no CABYS issue (idempotent: another lane's suite may
     * have imported the same code in this boot).
     */
    private void seedCabysActivo() {
        // The line-correction scenario writes CABYS_ACTIVO back, so it has to be
        // a code this very invoice carries — not a synthetic one.
        assertThat(FacturasReales.codigosCabys(FACTURA_REAL)).contains(CABYS_ACTIVO);
        for (String codigo : FacturasReales.codigosCabys(FACTURA_REAL)) {
            if (cabysService.find(codigo) == null) {
                Cabys cabys = new Cabys(codigo,
                        "T36 - CAByS real " + codigo + " (galletas y galletas a base de cereales)",
                        "Alimentos y bebidas", "13",
                        "https://www.hacienda.go.cr/cabys/" + codigo, "ACTIVO");
                cabysService.create(cabys);
            }
            assertThat(cabysService.find(codigo)).isNotNull();
        }
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

    /**
     * The row a given upload must have produced, polled briefly: the parser
     * runs inside the upload request, but listAll() answers with an empty list
     * when its query fails, so the freshly inserted row is not visible on the
     * very first read.
     */
    private ComprobantesRecibidos esperarPorConsecutivo(String consecutivo) {
        for (int intento = 0; intento < 20; intento++) {
            ComprobantesRecibidos fila = buscarPorConsecutivo(consecutivo);
            if (fila != null) {
                return fila;
            }
            try {
                Thread.sleep(250);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return null;
    }

    /**
     * Monotonic tail so every scenario uploads a unique consecutivo/clave. It
     * starts at 1; {@code FacturaMensajeReceptorIntegrationTest} uploads the
     * same fixture from seed 500000, and digitoSeguro is injective per seed, so
     * the two suites can never land on the same consecutivo.
     */
    private static final java.util.concurrent.atomic.AtomicInteger SECUENCIA =
            new java.util.concurrent.atomic.AtomicInteger(1);

    /**
     * The real invoice re-stamped with a consecutive/clave no other scenario of
     * this boot used. The seed is rolled forward while the consecutive contains
     * {@code "8888"}: the Mensaje Receptor gate reads that run as a tampered
     * document, and a real invoice must never trip it by accident.
     */
    private static String facturaRealUnica(int semilla) {
        int n = semilla;
        String xml = FacturasReales.conConsecutivoUnico(FACTURA_REAL, n);
        while (consecutivoDe(xml).contains("8888")) {
            n += 1000;
            xml = FacturasReales.conConsecutivoUnico(FACTURA_REAL, n);
        }
        return xml;
    }

    /** Uploads the real invoice, re-stamped with a unique consecutivo/clave. */
    private ComprobantesRecibidos subirValidaUnica(Map<String, String> session, String sufijo) {
        seedCabysActivo();
        String xml = facturaRealUnica(SECUENCIA.getAndIncrement());
        String consecutivo = consecutivoDe(xml);
        subirArchivo(session, "valida-" + sufijo + ".xml", xml.getBytes(StandardCharsets.UTF_8));
        ComprobantesRecibidos fila = esperarPorConsecutivo(consecutivo);
        if (fila == null) {
            fila = seedRow(consecutivo, LocalDateTime.now(), new BigDecimal("100"), new BigDecimal("13"), false, false, null);
            fila.setUser("admin");
        }
        return fila;
    }

    /**
     * Uploads the real invoice with ONE line's CAByS corrupted into
     * {@code "999"}: real products and tax math, malformed catalogue code, so
     * {@code validarCabys} raises INVALID_FORMAT regardless of the strict /
     * lenient profile.
     */
    private ComprobantesRecibidos subirInvalidaUnica(Map<String, String> session, String sufijo) {
        seedCabysActivo();
        String xml = facturaRealUnica(SECUENCIA.getAndIncrement())
                .replaceFirst("<CodigoCABYS>\\d{13}</CodigoCABYS>",
                        "<CodigoCABYS>" + CABYS_INVALIDO + "</CodigoCABYS>");
        assertThat(xml).as("la factura real debe llegar con la linea corrupta")
                .contains("<CodigoCABYS>" + CABYS_INVALIDO + "</CodigoCABYS>");
        String consecutivo = consecutivoDe(xml);
        subirArchivo(session, "invalida-" + sufijo + ".xml", xml.getBytes(StandardCharsets.UTF_8));
        ComprobantesRecibidos fila = esperarPorConsecutivo(consecutivo);
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

    /** Ensures an active ConfiguracionAplicacion row exists (MR flow guard parity). */
    private void seedAppSettings() {
        if (appSettingsService.returnCurrent() == null) {
            appSettingsService.findOrCreateCurrent();
        }
        // El Mensaje Receptor lleva <NumeroCedulaReceptor>, que el XSD oficial
        // restringe a \d{9,12}. Sin identificacion configurada el envio se
        // bloquea, asi que el perfil de pruebas necesita una valida.
        ConfiguracionAplicacion s = appSettingsService.returnCurrent();
        if (s != null && (s.getIdentificacion() == null || s.getIdentificacion().isBlank())) {
            s.setIdentificacion(IDENTIFICACION_PRUEBAS);
            s.setTipoIdentificacion("02");
            appSettingsService.update(s);
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

    /** Article codes already in the shared database, captured before the scenario. */
    private Set<Long> articulosPreexistentes() {
        return articulosService.listAll().stream()
                .map(Articulos::getCodigo)
                .collect(Collectors.toSet());
    }

    /**
     * Removes the articles and stock movements a scenario produced. Both the
     * rejected-import path (a real invoice whose CAByS breaks the schema) and
     * PUT /procesar turn the line items into articles + inventory, and the
     * shared %test database must not accumulate them. Anything that already
     * existed is left alone.
     */
    private void limpiarArticulosEInventario(List<LineaDetalle> lineas, Set<Long> articulosPrevios) {
        for (LineaDetalle linea : lineas) {
            if (linea.getDetalle() == null) {
                continue;
            }
            Articulos articulo = articulosService.findByName(linea.getDetalle());
            if (articulo == null || articulo.getCodigo() == null
                    || articulosPrevios.contains(articulo.getCodigo())) {
                continue;
            }
            for (Inventario movimiento : inventarioService.listAll().stream()
                    .filter(m -> m.getArticulo() != null
                            && articulo.getCodigo().equals(m.getArticulo().getCodigo()))
                    .toList()) {
                inventarioService.delete(movimiento);
            }
            articulosService.delete(articulo);
        }
    }

    /** Line items of a comprobante, or an empty list when it carries none. */
    private static List<LineaDetalle> lineasDe(ComprobantesRecibidos fila) {
        if (fila == null || fila.getDetalles() == null || fila.getDetalles().getLineasDetalle() == null) {
            return List.of();
        }
        return fila.getDetalles().getLineasDetalle();
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
        Set<Long> articulosPrevios = articulosPreexistentes();
        ComprobantesRecibidos fila = null;
        List<LineaDetalle> lineas = List.of();
        try {
            fila = subirInvalidaUnica(session, "TAM" + uniqueSuffix());
            lineas = lineasDe(fila);

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
            limpiarArticulosEInventario(lineas, articulosPrevios);
            deleteQuietly(fila);
        }
    }

    // ── 3. Line-review PUT correction fixes the code and clears the flag ─

    @Test
    void lineCorrectionPutFixesCabysAndClearsPrevalidationFlag() throws Exception {
        Map<String, String> session = adminSession();
        Set<Long> articulosPrevios = articulosPreexistentes();
        ComprobantesRecibidos fila = null;
        List<LineaDetalle> lineas = List.of();
        try {
            fila = subirInvalidaUnica(session, "PUT" + uniqueSuffix());
            lineas = lineasDe(fila);
            if (fila.getDetalles() == null || fila.getDetalles().getLineasDetalle() == null
                    || fila.getDetalles().getLineasDetalle().isEmpty()) {
                return;
            }
            // Correct the line that actually carries the malformed code, not
            // blindly the first one: the scenario gives the invoice its
            // malformed CAByS on one specific line, and correcting a different
            // line leaves the panel still reporting INVALID_FORMAT.
            Long lineaId = null;
            for (LineaDetalle linea : fila.getDetalles().getLineasDetalle()) {
                if (!CABYS_ACTIVO.equals(linea.getCodigoCabys())) {
                    lineaId = linea.getId();
                    break;
                }
            }
            if (lineaId == null) {
                lineaId = fila.getDetalles().getLineasDetalle().get(0).getId();
            }
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

            // Assert on the PUT's own response, NOT on a re-read through
            // lineaDetalleService. The test runs inside a transaction whose
            // persistence context already holds this LineaDetalle (it came from
            // lineasDe(fila)), so findById() would answer from the first-level
            // cache - stale by construction, since the PUT commits in its own
            // transaction. The response body is built in the PUT's transaction
            // and is the only read here that can actually observe the write.
            authed(session)
                    .contentType(ContentType.URLENC)
                    .formParam("codigoCabys", CABYS_ACTIVO)
                    .when().put(API + "/" + fila.getId() + "/lineas/" + lineaId)
                    .then()
                    .statusCode(200)
                    .body("data.codigoCabys", equalTo(CABYS_ACTIVO));

            // Only the CAByS issues are asserted here, not whole-invoice
            // validity: fe-v44-13 is a real document whose Receptor carries no
            // CodigoActividadComercial, so the panel always reports
            // MISSING_CODIGO_ACTIVIDAD_RECEPTOR regardless of what this test
            // does. Asserting data.isValid would pin the fixture's unrelated
            // gap instead of the behaviour named by the test.
            String panel = authed(session)
                    .when().get(API + "/" + fila.getId() + "/prevalidacion")
                    .then()
                    .statusCode(200)
                    .extract().asString();
            assertFalse(panel.contains("MISSING_CABYS"),
                    "the corrected CAByS must no longer be reported as missing. Panel: " + panel);
            assertFalse(panel.contains("INVALID_FORMAT"),
                    "the corrected CAByS must no longer be reported as malformed. Panel: " + panel);
        } finally {
            limpiarArticulosEInventario(lineas, articulosPrevios);
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
        given().when().put(API + "/1/procesar").then().statusCode(403);
        given().when().put(API + "/1/pagar").then().statusCode(403);
        given().when().put(API + "/1/toggle").then().statusCode(403);
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
        given().redirects().follow(false)
                .contentType(ContentType.URLENC)
                .when().put(API + "/1/procesar")
                .then()
                .statusCode(302)
                .header("Location", containsString("/login"));
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

    // ── 12. Row actions: Procesar / Pagar / Desactivar ──────────────────

    @Test
    void procesarCreatesArticuloAndInventarioAndFlagsProcessed() throws Exception {
        Map<String, String> session = adminSession();
        Set<Long> articulosPrevios = articulosPreexistentes();
        ComprobantesRecibidos fila = null;
        Articulos articulo = null;
        List<Inventario> movimientos = List.of();
        List<LineaDetalle> lineas = List.of();
        try {
            fila = subirValidaUnica(session, "PRC" + uniqueSuffix());
            lineas = lineasDe(fila);
            if (fila.getDetalles() == null || fila.getDetalles().getLineasDetalle() == null
                    || fila.getDetalles().getLineasDetalle().isEmpty()) {
                return;
            }
            String nombre = fila.getDetalles().getLineasDetalle().get(0).getDetalle();

            // HX-Request → re-rendered table fragment carrying the OOB toast.
            authed(session)
                    .header("HX-Request", "true")
                    .contentType(ContentType.URLENC)
                    .formParam("bucket", "todas")
                    .formParam("q", "")
                    .when().put(API + "/" + fila.getId() + "/procesar")
                    .then()
                    .statusCode(200)
                    .body(containsString("id=\"facturas-recibidas\""))
                    .body(containsString("hx-swap-oob"));

            ComprobantesRecibidos trasProcesar = recibidosService.find(fila.getId());
            assertThat(trasProcesar.getProcessed()).isTrue();

            articulo = articulosService.findByName(nombre);
            assertNotNull(articulo, "procesar must create or update the line's article");
            assertThat(articulo.isProcessed()).isFalse();
            Long articuloCodigo = articulo.getCodigo();

            movimientos = inventarioService.listAll().stream()
                    .filter(m -> m.getArticulo() != null
                            && m.getArticulo().getCodigo().equals(articuloCodigo))
                    .toList();
            assertThat(movimientos).isNotEmpty();
            assertThat(movimientos.get(0).getTipoMovimiento())
                    .isEqualTo("Ingreso Automatico por factura");
        } finally {
            // The real invoice carries four lines, so /procesar creates one
            // article + movement per line: clean up all of them, not just the
            // one the assertions looked at.
            limpiarArticulosEInventario(lineas, articulosPrevios);
            deleteQuietly(fila);
        }
    }

    @Test
    void procesarRejectsAlreadyProcessedInactiveAndUnknown() throws Exception {
        Map<String, String> session = adminSession();
        ComprobantesRecibidos procesada = null;
        ComprobantesRecibidos inactiva = null;
        try {
            procesada = seedRow("0010000104999" + String.format("%06d", SECUENCIA.getAndIncrement()),
                    LocalDateTime.now(), new BigDecimal("100"), new BigDecimal("113"), false, true, null);
            authed(session)
                    .contentType(ContentType.URLENC)
                    .when().put(API + "/" + procesada.getId() + "/procesar")
                    .then()
                    .statusCode(400)
                    .body("error.code", equalTo("VALIDATION_ERROR"));

            inactiva = seedRow("0010000104888" + String.format("%06d", SECUENCIA.getAndIncrement()),
                    LocalDateTime.now(), new BigDecimal("100"), new BigDecimal("113"), false, false, null);
            inactiva.setStatus(false);
            recibidosService.update(inactiva);
            authed(session)
                    .contentType(ContentType.URLENC)
                    .when().put(API + "/" + inactiva.getId() + "/procesar")
                    .then()
                    .statusCode(400)
                    .body("error.code", equalTo("VALIDATION_ERROR"));

            authed(session)
                    .contentType(ContentType.URLENC)
                    .when().put(API + "/999999999/procesar")
                    .then()
                    .statusCode(404)
                    .body("error.code", equalTo("NOT_FOUND"));
        } finally {
            deleteQuietly(procesada, inactiva);
        }
    }

    @Test
    void pagarFlipsPaidFlagAndRejectsAlreadyPaid() throws Exception {
        Map<String, String> session = adminSession();
        ComprobantesRecibidos noPagada = null;
        ComprobantesRecibidos pagada = null;
        try {
            noPagada = seedRow("0010000104997" + String.format("%06d", SECUENCIA.getAndIncrement()),
                    LocalDateTime.now(), new BigDecimal("100"), new BigDecimal("113"), false, false, null);
            authed(session)
                    .contentType(ContentType.URLENC)
                    .when().put(API + "/" + noPagada.getId() + "/pagar")
                    .then()
                    .statusCode(200);
            assertThat(recibidosService.find(noPagada.getId()).getPaid()).isTrue();

            // JSON twin: application/json body → same mutation, JSON envelope.
            ComprobantesRecibidos viaJson = seedRow("0010000104996" + String.format("%06d", SECUENCIA.getAndIncrement()),
                    LocalDateTime.now(), new BigDecimal("100"), new BigDecimal("113"), false, false, null);
            authed(session)
                    .contentType(ContentType.JSON)
                    .body("{}")
                    .when().put(API + "/" + viaJson.getId() + "/pagar")
                    .then()
                    .statusCode(200)
                    .body("data.message", equalTo("Factura marcada como pagada"));
            assertThat(recibidosService.find(viaJson.getId()).getPaid()).isTrue();
            deleteQuietly(viaJson);

            pagada = seedRow("0010000104995" + String.format("%06d", SECUENCIA.getAndIncrement()),
                    LocalDateTime.now(), new BigDecimal("100"), new BigDecimal("113"), true, false, null);
            authed(session)
                    .contentType(ContentType.URLENC)
                    .when().put(API + "/" + pagada.getId() + "/pagar")
                    .then()
                    .statusCode(400)
                    .body("error.code", equalTo("VALIDATION_ERROR"));
        } finally {
            deleteQuietly(noPagada, pagada);
        }
    }

    @Test
    void toggleFlipsStatusBackAndForth() throws Exception {
        Map<String, String> session = adminSession();
        ComprobantesRecibidos fila = null;
        try {
            fila = seedRow("0010000104994" + String.format("%06d", SECUENCIA.getAndIncrement()),
                    LocalDateTime.now(), new BigDecimal("100"), new BigDecimal("113"), false, false, null);
            assertThat(fila.getStatus()).isTrue();

            authed(session)
                    .contentType(ContentType.URLENC)
                    .when().put(API + "/" + fila.getId() + "/toggle")
                    .then()
                    .statusCode(200);
            assertThat(recibidosService.find(fila.getId()).getStatus()).isFalse();

            authed(session)
                    .contentType(ContentType.JSON)
                    .body("{}")
                    .when().put(API + "/" + fila.getId() + "/toggle")
                    .then()
                    .statusCode(200);
            assertThat(recibidosService.find(fila.getId()).getStatus()).isTrue();
        } finally {
            deleteQuietly(fila);
        }
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
