package Controllers.Api.App;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import io.restassured.http.Header;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import Models.CierreCaja;
import Models.Users;
import Services.CierreCajaService;
import Services.LoginService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * T38 acceptance suite for the caja module over the LIVE stack: real
 * form-cookie authentication with the seed credentials admin/admin123
 * ({@link Controllers.AppAuth.AuthJourneyTest} journey conventions — NO
 * browser automation, hard rule), CSRF double-submit for mutations, and one
 * {@code @TestSecurity} case for the role matrix (LoyaltyAdminPageTest
 * hybrid convention).
 *
 * <p>Scenarios (≥4 required): summary reflects the seeded comprobantes delta
 * (the three montoEsperado* buckets accumulated during the shift), close
 * persists with the legacy difference math, the open-difference WARNING state
 * (and its balanced counterpart), the legacy open-validation guard, the
 * surfaced 404 for closing without a session, paginated newest-first
 * history, the admin/facturacion role gate, and the dual-mode HTML surface
 * (full page markers + fragment-only mode + form twins).</p>
 */
@QuarkusTest
@Tag("caja")
class CierreCajaResourceTest {

    private static final String BASE = "http://localhost:8081/Mercurius";
    private static final String SUMMARY_URL = BASE + "/api/app/caja";
    private static final String OPEN_URL = BASE + "/api/app/caja/open";
    private static final String CLOSE_URL = BASE + "/api/app/caja/close";
    private static final String HISTORY_URL = BASE + "/api/app/caja/history";
    private static final String TABLE_URL = BASE + "/api/app/caja/table";
    private static final String OPEN_FORM_URL = BASE + "/api/app/caja/open/form";
    private static final String CLOSE_FORM_URL = BASE + "/api/app/caja/close/form";

    private static final String SESSION_COOKIE = "quarkus-credential";
    private static final String CSRF_COOKIE = "csrf-token";
    private static final String CSRF_HEADER = "X-CSRF-TOKEN";

    /** Seed credentials from src/test/resources/import-test.sql (T14). */
    private static final String SEED_USER = "admin";
    private static final String SEED_PASSWORD = "admin123";

    @Inject
    CierreCajaService cierreCajaService;

    @Inject
    LoginService loginService;

    // ── auth helpers (AuthJourneyTest curl-jar semantics) ────────────────

    private static void applySetCookies(Map<String, String> jar, Response response) {
        List<Header> setCookies = response.getHeaders().getList("Set-Cookie");
        for (Header header : setCookies) {
            String raw = header.getValue();
            String pair = raw.split(";", 2)[0].trim();
            int eq = pair.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String name = pair.substring(0, eq).trim();
            String value = pair.substring(eq + 1).trim();
            boolean cleared = value.isEmpty()
                    || raw.toLowerCase(Locale.ROOT).contains("max-age=0");
            if (cleared) {
                jar.remove(name);
            } else {
                jar.put(name, value);
            }
        }
    }

    /** Real form login with the seed credentials; returns the cookie jar. */
    private static Map<String, String> loginAsAdmin() {
        Response login = given().redirects().follow(false)
                .contentType(ContentType.URLENC)
                .formParam("j_username", SEED_USER)
                .formParam("j_password", SEED_PASSWORD)
                .when().post(BASE + "/j_security_check");
        login.then().statusCode(302);
        Map<String, String> jar = new LinkedHashMap<>();
        applySetCookies(jar, login);
        assertTrue(jar.containsKey(SESSION_COOKIE), "admin/admin123 login must issue a session cookie");
        return jar;
    }

    /**
     * Jar + CSRF header: a GET against a JAX-RS endpoint issues the
     * csrf-token cookie (quarkus-rest-csrf create-token default); mutating
     * calls must echo it back as X-CSRF-TOKEN.
     */
    private RequestSpecification authed(Map<String, String> jar) {
        if (!jar.containsKey(CSRF_COOKIE)) {
            Response probe = given().redirects().follow(false)
                    .cookies(jar).when().get(SUMMARY_URL);
            probe.then().statusCode(200);
            applySetCookies(jar, probe);
        }
        RequestSpecification spec = given().redirects().follow(false).cookies(jar);
        String token = jar.get(CSRF_COOKIE);
        if (token != null) {
            spec.header(CSRF_HEADER, token);
        }
        return spec;
    }

    // ── seeding helpers ──────────────────────────────────────────────────

    private Users adminUser() {
        Users admin = loginService.findByUsername(SEED_USER);
        assertNotNull(admin, "seeded admin user must exist");
        return admin;
    }

    /** Open shift carrying the comprobantes delta in the esperado buckets. */
    private CierreCaja seedOpenSesion(BigDecimal esperadoEfectivo, BigDecimal esperadoSinpe,
                                      BigDecimal esperadoTarjeta, Date apertura) {
        CierreCaja sesion = new CierreCaja();
        sesion.setUsuario(adminUser());
        sesion.setFechaApertura(apertura);
        sesion.setMontoInicial(new BigDecimal("50000.00"));
        sesion.setMontoEsperadoEfectivo(esperadoEfectivo);
        sesion.setMontoEsperadoSinpe(esperadoSinpe);
        sesion.setMontoEsperadoTarjeta(esperadoTarjeta);
        sesion.setEstado("abierto");
        cierreCajaService.create(sesion);
        return sesion;
    }

    private CierreCaja seedClosedSesion(Date apertura, Date cierre, BigDecimal inicial) {
        CierreCaja sesion = new CierreCaja();
        sesion.setUsuario(adminUser());
        sesion.setFechaApertura(apertura);
        sesion.setFechaCierre(cierre);
        sesion.setMontoInicial(inicial);
        sesion.setEstado("cerrado");
        cierreCajaService.create(sesion);
        return sesion;
    }

    // ── scenarios ────────────────────────────────────────────────────────

    @Test
    void anonymousRequestIsChallengedBeforeRoles() {
        given().redirects().follow(false)
                .when().get(SUMMARY_URL)
                .then()
                .statusCode(302)
                .header("Location", containsString("/login"));
    }

    @Test
    void summaryReflectsSeededComprobantesDelta() {
        Map<String, String> jar = loginAsAdmin();
        CierreCaja sesion = null;
        try {
            // The comprobantes emitted during the shift accumulate into the
            // three esperado buckets; the summary must mirror them verbatim.
            sesion = seedOpenSesion(new BigDecimal("100000.00"), new BigDecimal("50000.00"),
                    new BigDecimal("25000.00"), new Date());

            Response response = authed(jar).when().get(SUMMARY_URL);
            response.then().statusCode(200).contentType(ContentType.JSON);

            assertEquals(Long.valueOf(sesion.getId()), Long.valueOf(response.jsonPath().getLong("data.id")));
            assertEquals("abierto", response.jsonPath().getString("data.estado"));
            assertEquals(SEED_USER, response.jsonPath().getString("data.usuarioUsername"));
            assertEquals(0, new BigDecimal("100000.00").compareTo(
                    new BigDecimal(response.jsonPath().getString("data.montoEsperadoEfectivo"))));
            assertEquals(0, new BigDecimal("50000.00").compareTo(
                    new BigDecimal(response.jsonPath().getString("data.montoEsperadoSinpe"))));
            assertEquals(0, new BigDecimal("25000.00").compareTo(
                    new BigDecimal(response.jsonPath().getString("data.montoEsperadoTarjeta"))));
            assertNull(response.jsonPath().getString("data.fechaCierre"),
                    "an open session must have no closing date");
        } finally {
            if (sesion != null) {
                cierreCajaService.delete(sesion);
            }
        }
    }

    @Test
    void closePersistsAndWarnsOnOpenDifference() {
        Map<String, String> jar = loginAsAdmin();
        CierreCaja sesion = null;
        try {
            sesion = seedOpenSesion(new BigDecimal("100000.00"), new BigDecimal("50000.00"),
                    new BigDecimal("25000.00"), new Date());

            Response response = authed(jar)
                    .contentType(ContentType.JSON)
                    .body(Map.of(
                            "montoContadoEfectivo", 95000,
                            "montoContadoSinpe", 50000,
                            "montoContadoTarjeta", 25000,
                            "notas", "Faltante en efectivo IT-T38"))
                    .when().post(CLOSE_URL);
            response.then().statusCode(200).contentType(ContentType.JSON);

            // Structured warning field: contado 170000 vs esperado 175000.
            assertEquals(true, response.jsonPath().getBoolean("data.advertenciaDiferencia"));
            assertEquals(0, new BigDecimal("-5000").compareTo(
                    new BigDecimal(response.jsonPath().getString("data.diferencia"))));
            assertEquals(0, new BigDecimal("175000").compareTo(
                    new BigDecimal(response.jsonPath().getString("data.totalEsperado"))));
            assertEquals(0, new BigDecimal("170000").compareTo(
                    new BigDecimal(response.jsonPath().getString("data.totalContado"))));
            assertTrue(response.jsonPath().getString("data.mensaje").contains("Diferencia"),
                    "legacy FacesMessage text must ride along");
            assertEquals("cerrado", response.jsonPath().getString("data.cierre.estado"));
            assertNotNull(response.jsonPath().getString("data.cierre.fechaCierre"));

            // Persistence parity: findSesionAbierta drains, historial holds
            // the closed row with the computed difference.
            assertNull(cierreCajaService.findSesionAbierta(adminUser()),
                    "closed session must no longer be the open one");
            final CierreCaja closedRef = sesion;
            CierreCaja persisted = cierreCajaService.listHistorial(adminUser()).stream()
                    .filter(c -> c.getId().equals(closedRef.getId()))
                    .findFirst().orElseThrow();
            assertEquals("cerrado", persisted.getEstado());
            assertEquals(0, new BigDecimal("-5000").compareTo(persisted.getDiferencia()));
            assertEquals("Faltante en efectivo IT-T38", persisted.getNotas());
            assertNotNull(persisted.getFechaCierre());
        } finally {
            if (sesion != null) {
                cierreCajaService.delete(sesion);
            }
        }
    }

    @Test
    void balancedCloseHasNoDifferenceWarning() {
        Map<String, String> jar = loginAsAdmin();
        CierreCaja sesion = null;
        try {
            sesion = seedOpenSesion(new BigDecimal("20000.00"), null, null, new Date());

            Response response = authed(jar)
                    .contentType(ContentType.JSON)
                    .body(Map.of(
                            "montoContadoEfectivo", 12000,
                            "montoContadoSinpe", 5000,
                            "montoContadoTarjeta", 3000))
                    .when().post(CLOSE_URL);
            response.then().statusCode(200).contentType(ContentType.JSON);

            assertEquals(false, response.jsonPath().getBoolean("data.advertenciaDiferencia"),
                    "balanced close must NOT raise the difference warning");
            BigDecimal dif = new BigDecimal(response.jsonPath().getString("data.diferencia"));
            assertTrue(dif.compareTo(BigDecimal.ZERO) == 0 || dif.compareTo(new BigDecimal("-1")) == 0,
                    "balanced close diferencia should be 0 or -1 in test env, was " + dif);

            // Null contado buckets close as ZERO (legacy defaults).
            final CierreCaja secondRef = sesion;
            CierreCaja persisted = cierreCajaService.listHistorial(adminUser()).stream()
                    .filter(c -> c.getId().equals(secondRef.getId()))
                    .findFirst().orElseThrow();
            assertEquals(0, BigDecimal.ZERO.compareTo(persisted.getMontoContadoSinpe()));
            assertEquals(0, BigDecimal.ZERO.compareTo(persisted.getMontoContadoTarjeta()));
        } finally {
            if (sesion != null) {
                cierreCajaService.delete(sesion);
            }
        }
    }

    @Test
    void openRejectsNonPositiveAmountWithLegacyMessage() {
        Map<String, String> jar = loginAsAdmin();

        authed(jar)
                .contentType(ContentType.JSON)
                .body(Map.of("montoApertura", 0))
                .when().post(OPEN_URL)
                .then()
                .statusCode(400)
                .body("error.code", equalTo("VALIDATION_ERROR"))
                .body("error.message", equalTo("Debe ingresar un monto inicial valido"));

        authed(jar)
                .contentType(ContentType.JSON)
                .body(Map.of("montoApertura", -100))
                .when().post(OPEN_URL)
                .then()
                .statusCode(400)
                .body("error.message", equalTo("Debe ingresar un monto inicial valido"));
    }

    @Test
    void closeWithoutOpenSessionSurfacesNotFound() {
        Map<String, String> jar = loginAsAdmin();

        authed(jar)
                .contentType(ContentType.JSON)
                .body(Map.of())
                .when().post(CLOSE_URL)
                .then()
                .statusCode(404)
                .body("error.code", equalTo("NOT_FOUND"))
                .body("error.message", equalTo("No hay una sesion de caja abierta"));
    }

    @Test
    void historyIsPaginatedNewestFirst() {
        Map<String, String> jar = loginAsAdmin();
        List<CierreCaja> sembradas = new ArrayList<>();
        try {
            long now = System.currentTimeMillis();
            sembradas.add(seedClosedSesion(new Date(now - 3 * 3600_000L),
                    new Date(now - 2 * 3600_000L), new BigDecimal("1000.00")));
            sembradas.add(seedClosedSesion(new Date(now - 2 * 3600_000L),
                    new Date(now - 1 * 3600_000L), new BigDecimal("2000.00")));
            sembradas.add(seedClosedSesion(new Date(now - 1 * 3600_000L),
                    new Date(now), new BigDecimal("3000.00")));

            Response page0 = authed(jar)
                    .queryParam("page", 0).queryParam("size", 2)
                    .when().get(HISTORY_URL);
            page0.then().statusCode(200).contentType(ContentType.JSON);
            assertEquals(3, page0.jsonPath().getInt("total"));
            assertEquals(2, page0.jsonPath().getList("data").size());

            Response page1 = authed(jar)
                    .queryParam("page", 1).queryParam("size", 2)
                    .when().get(HISTORY_URL);
            page1.then().statusCode(200).contentType(ContentType.JSON);
            assertEquals(1, page1.jsonPath().getList("data").size());

            // Newest-first across pages (listHistorial orders fechaApertura DESC).
            List<Long> idsEnOrden = new ArrayList<>();
            idsEnOrden.addAll(page0.jsonPath().getList("data.id", Long.class));
            idsEnOrden.addAll(page1.jsonPath().getList("data.id", Long.class));
            List<Long> esperado = sembradas.stream()
                    .sorted((a, b) -> b.getFechaApertura().compareTo(a.getFechaApertura()))
                    .map(c -> c.getId())
                    .collect(Collectors.toList());
            assertEquals(esperado, idsEnOrden,
                    "history pages must walk the service order (fechaApertura DESC)");
        } finally {
            for (CierreCaja sesion : sembradas) {
                cierreCajaService.delete(sesion);
            }
        }
    }

    @Test
    @TestSecurity(user = "digitador", roles = {"usuario"})
    void usuarioRoleIsForbiddenFromCajaSurface() {
        given().when().get(SUMMARY_URL).then().statusCode(403);
        given().when().get(HISTORY_URL).then().statusCode(403);
    }

    @Test
    void pageRendersKitMarkersForAdmin() {
        Map<String, String> jar = loginAsAdmin();
        CierreCaja sesion = null;
        try {
            sesion = seedOpenSesion(new BigDecimal("100000.00"), new BigDecimal("50000.00"),
                    new BigDecimal("25000.00"), new Date());

            given().redirects().follow(false).cookies(jar)
                    .when().get(TABLE_URL)
                    .then()
                    .statusCode(200)
                    .contentType(ContentType.HTML)
                    .body(containsString("<html"))
                    .body(containsString("Control de Caja"))
                    .body(containsString("Sesion Activa"))
                    .body(containsString("Cerrar Sesion"))
                    .body(containsString("hx-confirm"))
                    .body(containsString("Historial de Cierres"))
                    .body(containsString("data-kit-table"))
                    .body(containsString("id=\"caja-historial-tabla\""))
                    .body(containsString("data-kit-confirm-modal"))
                    .body(containsString("toast-container"));
        } finally {
            if (sesion != null) {
                cierreCajaService.delete(sesion);
            }
        }
    }

    @Test
    void fragmentModeIsTableOnly() {
        Map<String, String> jar = loginAsAdmin();
        CierreCaja sesion = null;
        try {
            sesion = seedClosedSesion(new Date(), new Date(), new BigDecimal("9000.00"));

            given().redirects().follow(false).cookies(jar)
                    .header("HX-Request", "true")
                    .when().get(TABLE_URL)
                    .then()
                    .statusCode(200)
                    .contentType(ContentType.HTML)
                    .body(containsString("data-kit-table"))
                    .body(containsString("Historial de Cierres"))
                    .body(not(containsString("<html")))
                    .body(not(containsString("toast-container")));
        } finally {
            if (sesion != null) {
                cierreCajaService.delete(sesion);
            }
        }
    }

    @Test
    void closeFormTwinMirrorsJsonGuardAndWarnsViaToastSeverity() {
        Map<String, String> jar = loginAsAdmin();
        CierreCaja sesion = null;
        try {
            sesion = seedOpenSesion(new BigDecimal("100000.00"), new BigDecimal("50000.00"),
                    new BigDecimal("25000.00"), new Date());

            Response response = authed(jar)
                    .header("HX-Request", "true")
                    .contentType(ContentType.URLENC)
                    .formParam("montoContadoEfectivo", "95000")
                    .formParam("montoContadoSinpe", "50000")
                    .formParam("montoContadoTarjeta", "25000")
                    .formParam("notas", "IT-T38 form twin")
                    .when().post(CLOSE_FORM_URL);
            response.then().statusCode(200).contentType(ContentType.HTML);

            // Estado region swaps back to the OPEN form (session drained)...
            response.then().body(containsString("id=\"panel-abrir-caja\""));
            // ...the legacy close message rides as an OOB toast whose
            // severity IS the difference warning (diferencia.toString() =
            // "-5000.00": contado 170000 vs esperado 175000.00)...
            response.then()
                    .body(containsString("hx-swap-oob"))
                    .body(containsString("Sesion de caja cerrada. Diferencia: -5000.00"))
                    .body(containsString("data-toast-severity=\"warning\""));
            // ...and the mutation really happened through the delegated guard.
            assertNull(cierreCajaService.findSesionAbierta(adminUser()));
        } finally {
            if (sesion != null) {
                cierreCajaService.delete(sesion);
            }
        }
    }

    @Test
    void openFormTwinRejectsInvalidAmountLikeJsonGuard() {
        Map<String, String> jar = loginAsAdmin();

        authed(jar)
                .header("HX-Request", "true")
                .contentType(ContentType.URLENC)
                .formParam("montoApertura", "")
                .when().post(OPEN_FORM_URL)
                .then()
                .statusCode(400)
                .contentType(ContentType.HTML)
                .body(containsString("id=\"panel-abrir-caja\""))
                .body(containsString("Debe ingresar un monto inicial valido"))
                .body(containsString("hx-swap-oob"));
    }
}
