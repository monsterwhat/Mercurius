package e2e;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import Models.Cabys;
import Services.AppSettingsService;
import Services.CabysService;

/**
 * F3 NORTH-STAR end-to-end acceptance journey — THE definition of "the app
 * works" for the JSF-to-API migration. Intentionally RED until the migration
 * completes; disabled so it never affects current gates.
 *
 * <p>Executable twin: {@code scripts/e2e-journey.ps1} (same nine steps, same
 * payloads, same assertions). Step-by-step contract documentation:
 * {@code docs/e2e-journey.md}.</p>
 *
 * <p>Journey sequence:</p>
 * <ol>
 *   <li>GET  /Mercurius/login                          -> 200 + j_security_check form</li>
 *   <li>POST /Mercurius/j_security_check               -> 302 + quarkus-credential cookie</li>
 *   <li>POST /Mercurius/api/app/articulos              -> 201</li>
 *   <li>POST /Mercurius/api/app/inventario/ajustes     -> 201 positive stock</li>
 *   <li>POST /Mercurius/api/app/facturas-recibidas/upload (v4.4 fixture)
 *       + GET /{id}/prevalidacion                      -> PASS panel</li>
 *   <li>POS sale: scan x2 -> cart qty=2 -> override-authorize ->
 *       payment-entries [efectivo+SINPE] -> facturar (puntos) -> {pdfUrl}
 *       -> GET pdfUrl                                  -> %PDF- magic bytes</li>
 *   <li>POST /api/app/recibos/{id}/pagar on a seeded credito receipt
 *                                                      -> paid flipped true</li>
 *   <li>POST /api/app/export xlsx(stock-alerts)        -> PKx03x04 magic bytes;
 *       POST /api/app/export pdf(articulos)            -> %PDF- magic bytes</li>
 *   <li>POST /api/app/auth/logout                      -> 303; replay old
 *       cookie on /me                                  -> 401/302</li>
 * </ol>
 *
 * <p>Preconditions satisfied inside this class (mirrors import-test.sql +
 * FacturasRecibidasResourceTest seeding): Users admin/admin123, Departamento
 * id=1, Familia id=1, Clients code=1 are seeded by import-test.sql; the two
 * CAByS rows and the AppSettings row required by the facturar settings gate
 * are seeded below via CDI services.</p>
 */
@QuarkusTest
@Tag("e2e")
@Disabled("migration-completion")
class JourneyE2ETest {

    private static final String BASE = "/Mercurius";
    private static final String API_ARTICULOS = BASE + "/api/app/articulos";
    private static final String API_INVENTARIO = BASE + "/api/app/inventario";
    private static final String API_RECIBIDAS = BASE + "/api/app/facturas-recibidas";
    private static final String API_RECIBOS = BASE + "/api/app/recibos";
    private static final String API_POS = BASE + "/api/app/pos";
    private static final String API_EXPORT = BASE + "/api/app/export";
    private static final String API_AUTH = BASE + "/api/app/auth";

    /** Committed v4.4-shaped valid fixture (FacturasRecibidasResourceTest parity). */
    private static final String FIXTURE_RESOURCE = "/fixtures/recibidos/factura-recibida-valida.xml";
    private static final String FIXTURE_CONSEC = "00100001040000000036";

    /** CAByS for the created article: impuesto 13 makes precioFinal deterministic. */
    private static final String CABYS_ARTICULO = "501010101";
    /** CAByS carried by the fixture's LineaDetalle; prevalidation wants it ACTIVO. */
    private static final String CABYS_FIXTURE = "0111010010010";

    /** Monotonic tail so re-runs never trip the parser's duplicate-consecutivo skip. */
    private static final AtomicInteger SECUENCIA = new AtomicInteger(1);

    @Inject
    CabysService cabysService;

    @Inject
    AppSettingsService appSettingsService;

    @Test
    void fullAcceptanceJourney() throws Exception {
        // ── Prerequisites (idempotent seeds) ────────────────────────────
        seedCabysActivo(CABYS_ARTICULO, "E2E journey - articulo generico", "13");
        seedCabysActivo(CABYS_FIXTURE, "T36 - Animales bovinos para reproduccion", "0");
        // Settings gate of POST /pos/facturar: an AppSettings row must exist
        // and estatus != FALSE (findOrCreateCurrent creates the default).
        appSettingsService.findOrCreateCurrent();

        // ── STEP 1: login page renders the j_security_check form ────────
        Response loginPage = given().redirects().follow(false)
                .when().get(BASE + "/login");
        loginPage.then().statusCode(200).body(containsString("j_security_check"));

        // ── STEP 2: form login -> 302 + quarkus-credential cookie ───────
        Map<String, String> cookies = adminSession();

        // ── STEP 3: create articulo -> 201 ──────────────────────────────
        String barcode = "E2EJ" + uniqueSuffix();
        Map<String, Object> articuloForm = new HashMap<>();
        articuloForm.put("nombre", "E2E Journey Articulo " + barcode);
        articuloForm.put("codigoBarra", barcode);
        articuloForm.put("descripcion", "Created by JourneyE2ETest");
        articuloForm.put("unidadMedida", "Unid");
        articuloForm.put("unidadMedidaComercial", "Unidad");
        articuloForm.put("departamentoId", 1); // import-test.sql 'Departamento General'
        articuloForm.put("familiaId", 1);      // import-test.sql 'Familia General'
        articuloForm.put("cabysCodigo", CABYS_ARTICULO);
        articuloForm.put("precioCostoSinIVA", new BigDecimal("10000"));
        articuloForm.put("porcentajeUtilidad", new BigDecimal("20"));
        articuloForm.put("exento", false);
        articuloForm.put("stockOptimo", 50);
        articuloForm.put("diasStockSeguridad", 7);
        Response createArticulo = authed(cookies)
                .contentType(ContentType.JSON)
                .body(articuloForm)
                .when().post(API_ARTICULOS);
        createArticulo.then().statusCode(201);
        long articuloId = createArticulo.jsonPath().getLong("data.codigo");
        assertTrue(articuloId > 0, "created articulo must carry its codigo");

        // ── STEP 4: inventario ajuste creates positive stock -> 201 ─────
        Map<String, Object> ajuste = new HashMap<>();
        ajuste.put("articuloId", articuloId);
        ajuste.put("cantidad", new BigDecimal("25"));
        ajuste.put("tipoMovimiento", "Ajuste manual");
        ajuste.put("notas", "E2E journey initial stock");
        authed(cookies)
                .contentType(ContentType.JSON)
                .body(ajuste)
                .when().post(API_INVENTARIO + "/ajustes")
                .then()
                .statusCode(201);

        // ── STEP 5: upload v4.4 fixture -> persisted + prevalidation PASS ─
        int n = SECUENCIA.getAndIncrement();
        String consecContado = "00100001047777" + String.format("%06d", n);
        String claveContado = "5062508250000010100010000000101"
                + String.format("%019d", 300000 + n);
        byte[] xmlContado = fixtureBytes(FIXTURE_RESOURCE, FIXTURE_CONSEC,
                consecContado, claveContado);
        subirXml(cookies, "e2e-contado-" + uniqueSuffix() + ".xml", xmlContado);
        long facturaContadoId = idDeConsecutivo(cookies, consecContado);
        authed(cookies)
                .when().get(API_RECIBIDAS + "/" + facturaContadoId + "/prevalidacion")
                .then()
                .statusCode(200)
                .body("data.isValid", equalTo(true))
                .body("data.errorCount", equalTo(0));

        // Credito variant (CondicionVenta 02 + PlazoCredito 30) feeds the
        // Recibos pendientes bucket consumed by STEP 7.
        int m = SECUENCIA.getAndIncrement();
        String consecCredito = "00100001046666" + String.format("%06d", m);
        String claveCredito = "5062508250000010100010000000101"
                + String.format("%019d", 400000 + m);
        byte[] xmlBase = fixtureBytes(FIXTURE_RESOURCE, FIXTURE_CONSEC,
                consecCredito, claveCredito);
        String xmlCredito = new String(xmlBase, StandardCharsets.UTF_8)
                .replace("<CondicionVenta>01<", "<CondicionVenta>02<")
                .replace("<PlazoCredito>0<", "<PlazoCredito>30<");
        subirXml(cookies, "e2e-credito-" + uniqueSuffix() + ".xml",
                xmlCredito.getBytes(StandardCharsets.UTF_8));
        long reciboId = idDeConsecutivo(cookies, consecCredito);
        assertEquals(false, paidDeConsecutivo(cookies, consecCredito),
                "seeded receipt must start unpaid");

        // ── STEP 6: POS sale ────────────────────────────────────────────
        // scan x2 -> cart snapshot qty=2
        Map<String, Object> scan = new HashMap<>();
        scan.put("codigoBarra", barcode);
        scan.put("cantidad", new BigDecimal("1"));
        for (int i = 0; i < 2; i++) {
            authed(cookies)
                    .contentType(ContentType.JSON)
                    .body(scan)
                    .when().post(API_POS + "/scan")
                    .then()
                    .statusCode(200);
        }
        Response cart = authed(cookies).when().get(API_POS + "/cart");
        cart.then().statusCode(200);
        BigDecimal qty = cart.jsonPath().getBigDecimal("data.items[0].cantidad");
        assertNotNull(qty, "cart snapshot must contain the scanned line");
        assertEquals(0, qty.compareTo(BigDecimal.TWO),
                "cart snapshot qty must be 2 after two scans");
        BigDecimal total = cart.jsonPath().getBigDecimal("data.totalCarrito");

        // client selection (puntos redemption requires a selected client)
        Map<String, Object> clientBody = new HashMap<>();
        clientBody.put("clientCode", 1); // import-test.sql 'Cliente Contado'
        authed(cookies)
                .contentType(ContentType.JSON)
                .body(clientBody)
                .when().post(API_POS + "/client")
                .then()
                .statusCode(200);

        // split payment entries: efectivo covers the total, SINPE adds change
        BigDecimal efectivo = total == null ? new BigDecimal("999999") : total;
        Map<String, Object> pagoEfectivo = new HashMap<>();
        pagoEfectivo.put("metodoPago", "01");
        pagoEfectivo.put("monto", efectivo);
        Map<String, Object> pagoSinpe = new HashMap<>();
        pagoSinpe.put("metodoPago", "06");
        pagoSinpe.put("monto", new BigDecimal("1000"));
        List<Map<String, Object>> pagos = List.of(pagoEfectivo, pagoSinpe);
        Response paymentEntries = authed(cookies)
                .contentType(ContentType.JSON)
                .body(pagos)
                .when().post(API_POS + "/payment-entries");
        paymentEntries.then().statusCode(200);
        // BigDecimal-aware comparison (JSON numbers may parse as Double).
        BigDecimal vuelto = paymentEntries.jsonPath().getBigDecimal("data.vuelto");
        assertNotNull(vuelto, "payment-entries must compute vuelto");
        assertTrue(vuelto.compareTo(BigDecimal.ZERO) >= 0,
                "split payments must cover the total (vuelto >= 0), got: " + vuelto);

        // supervisor override authorization (legacy parity: same admin creds)
        Map<String, Object> overrideAuth = new HashMap<>();
        overrideAuth.put("username", "admin");
        overrideAuth.put("password", "admin123");
        authed(cookies)
                .contentType(ContentType.JSON)
                .body(overrideAuth)
                .when().post(API_POS + "/override-authorize")
                .then()
                .statusCode(200)
                .body("data.authorizedBy", equalTo("admin"));

        // facturar with puntos redemption -> 200 {pdfUrl}
        Map<String, Object> facturarBody = new HashMap<>();
        facturarBody.put("tipoDocumento", "04"); // TE default
        facturarBody.put("puntosARedimir", new BigDecimal("10"));
        Response facturar = authed(cookies)
                .contentType(ContentType.JSON)
                .body(facturarBody)
                .when().post(API_POS + "/facturar");
        facturar.then().statusCode(200);
        String pdfUrl = facturar.jsonPath().getString("data.pdfUrl");
        assertNotNull(pdfUrl, "facturar must return {pdfUrl}");

        // invoice PDF streams real PDF bytes
        byte[] pdf = given().redirects().follow(false)
                .when().get(pdfUrl)
                .then()
                .statusCode(200)
                .extract().asByteArray();
        assertTrue(pdf.length >= 5 && pdf[0] == '%' && pdf[1] == 'P'
                && pdf[2] == 'D' && pdf[3] == 'F' && pdf[4] == '-',
                "invoice download must start with %PDF- magic bytes");

        // ── STEP 7: Recibos pay/process flips the seeded receipt ────────
        authed(cookies)
                .contentType(ContentType.JSON)
                .body("{}")
                .when().post(API_RECIBOS + "/" + reciboId + "/pagar")
                .then()
                .statusCode(200);
        assertEquals(true, paidDeConsecutivo(cookies, consecCredito),
                "recibo pagar must flip paid=false -> true");

        // ── STEP 8: exports stream real workbook/PDF magic bytes ────────
        byte[] xlsx = authed(cookies)
                .contentType(ContentType.URLENC)
                .formParam("type", "xlsx")
                .formParam("dataset", "stock-alerts")
                .when().post(API_EXPORT)
                .then()
                .statusCode(200)
                .extract().asByteArray();
        assertTrue(xlsx.length >= 4 && xlsx[0] == 'P' && xlsx[1] == 'K'
                && xlsx[2] == 0x03 && xlsx[3] == 0x04,
                "xlsx export must start with PKx03x04 magic bytes");

        byte[] pdfExport = authed(cookies)
                .contentType(ContentType.URLENC)
                .formParam("type", "pdf")
                .formParam("dataset", "articulos")
                .when().post(API_EXPORT)
                .then()
                .statusCode(200)
                .extract().asByteArray();
        assertTrue(pdfExport.length >= 5 && pdfExport[0] == '%' && pdfExport[1] == 'P'
                && pdfExport[2] == 'D' && pdfExport[3] == 'F' && pdfExport[4] == '-',
                "pdf export must start with %PDF- magic bytes");

        // ── STEP 9: logout invalidates the session server-side ──────────
        String oldCredential = cookies.get("quarkus-credential");
        assertNotNull(oldCredential, "login must have issued quarkus-credential");
        Response logout = authed(cookies)
                .when().post(API_AUTH + "/logout");
        logout.then().statusCode(303);
        String location = logout.getHeader("Location");
        assertNotNull(location, "logout must carry a seeOther Location header");
        assertTrue(location.endsWith("/Mercurius/login"),
                "logout must redirect to the login page, got: " + location);

        // replaying the OLD cookie must no longer authenticate
        given().redirects().follow(false)
                .cookie("quarkus-credential", oldCredential)
                .when().get(API_AUTH + "/me")
                .then()
                .statusCode(anyOf(equalTo(401), equalTo(302)));
    }

    // ── Auth helpers (FacturasRecibidasResourceTest parity) ─────────────

    /**
     * Full form-auth dance: GET the login page (cookie priming), POST
     * j_security_check, collect the issued session cookies. Asserts the 302
     * and the quarkus-credential cookie (STEP 1+2 of the journey).
     */
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
        assertTrue(cookies.containsKey("quarkus-credential"),
                "form login must issue the quarkus-credential cookie");
        return cookies;
    }

    /** Authenticated spec with CSRF header when the csrf cookie exists. */
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

    // ── Fixture / seeding / lookup helpers ──────────────────────────────

    /** Loads a committed fixture and re-stamps Clave/Consecutivo uniquely. */
    private static byte[] fixtureBytes(String ruta, String consecutivoOriginal,
                                       String consecutivoNuevo, String claveNueva) throws Exception {
        try (InputStream in = JourneyE2ETest.class.getResourceAsStream(ruta)) {
            assertTrue(in != null, "fixture must be on the test classpath: " + ruta);
            String xml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            xml = xml.replace(consecutivoOriginal, consecutivoNuevo);
            // The Clave only needs to be present, unique and 50 digits long.
            xml = xml.replaceAll(">\\d{50}<", ">" + claveNueva + "<");
            return xml.getBytes(StandardCharsets.UTF_8);
        }
    }

    /** Uploads one XML through POST /upload (JSON mode) asserting exito=true. */
    private static void subirXml(Map<String, String> cookies, String nombre, byte[] contenido) {
        authed(cookies)
                .contentType(ContentType.MULTIPART)
                .multiPart("files", nombre, contenido, "application/xml")
                .when().post(API_RECIBIDAS + "/upload")
                .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("data.resultados[0].fileName", equalTo(nombre))
                .body("data.resultados[0].exito", equalTo(true));
    }

    /** Finds the parser-persisted comprobante id for a consecutivo (inbox JSON). */
    private static long idDeConsecutivo(Map<String, String> cookies, String consecutivo) {
        Response page = authed(cookies)
                .queryParam("bucket", "todas")
                .queryParam("q", consecutivo)
                .when().get(API_RECIBIDAS);
        page.then().statusCode(200);
        List<Map<String, Object>> rows = page.jsonPath().getList("data");
        assertTrue(rows != null && !rows.isEmpty(),
                "uploaded comprobante must appear in the inbox: " + consecutivo);
        for (Map<String, Object> row : rows) {
            if (consecutivo.equals(row.get("consecutivo"))) {
                Number id = (Number) row.get("id");
                assertNotNull(id, "inbox row must carry its id");
                return id.longValue();
            }
        }
        throw new IllegalStateException("consecutivo not found in inbox: " + consecutivo);
    }

    /** Reads the paid flag of a receipt row (Recibos state assertion). */
    private static boolean paidDeConsecutivo(Map<String, String> cookies, String consecutivo) {
        Response page = authed(cookies)
                .queryParam("bucket", "todas")
                .queryParam("q", consecutivo)
                .when().get(API_RECIBIDAS);
        page.then().statusCode(200);
        List<Map<String, Object>> rows = page.jsonPath().getList("data");
        for (Map<String, Object> row : rows) {
            if (consecutivo.equals(row.get("consecutivo"))) {
                return Boolean.TRUE.equals(row.get("paid"));
            }
        }
        throw new IllegalStateException("consecutivo not found in inbox: " + consecutivo);
    }

    /** Idempotent ACTIVO CAByS seed (FacturasRecibidasResourceTest parity). */
    private void seedCabysActivo(String codigo, String descripcion, String impuesto) {
        if (cabysService.find(codigo) == null) {
            cabysService.create(new Cabys(codigo, descripcion, "E2E", impuesto,
                    "https://example.com/cabys/" + codigo, "ACTIVO"));
        }
    }
}
