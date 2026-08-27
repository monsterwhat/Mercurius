package Controllers.Api.App;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import java.util.HashMap;
import java.util.Map;
import Models.Registros.Alertas;
import Services.AlertasService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * T30 view-half acceptance suite for the Registros Internos page served by
 * {@link AlertasResource}: authenticated full-page GET carries the kit
 * markers, HX-Request returns ONLY the tabla fragment, the unreadOnly facet
 * narrows to a seeded unread row and round-trips through the ack URLs, the
 * severity badge follows the _kit/toast-item color vocabulary, the HTMX ack
 * channel marks the alert read and answers the refreshed fragment while the
 * pre-existing JSON ack contract stays green, and a page beyond the last
 * yields the empty state instead of an error.
 *
 * <p>Auth mirrors AuthJourneyTest/CsrfEnforcementTest: real form-cookie login
 * over RestAssured (seed admin/admin123) + {@code X-CSRF-TOKEN} header
 * matching the {@code csrf-token} cookie.</p>
 */
@QuarkusTest
@Tag("w4b-views")
class RegistrosAlertasPageTest extends support.ContextPathIsolation {

    private static final String BASE = "/Mercurius";
    private static final String PAGE_URL = BASE + "/api/app/alertas/pagina";
    private static final String CSRF_COOKIE = "csrf-token";
    private static final String CSRF_HEADER = "X-CSRF-TOKEN";

    @Inject
    AlertasService alertasService;

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
        String token = cookies.get(CSRF_COOKIE);
        if (token != null) {
            spec.header(CSRF_HEADER, token);
        }
        return spec;
    }

    /** Seeds one unread system alert identifiable by its exact mensaje. */
    private Alertas seedUnread(String marker, String tipo) {
        alertasService.registrarAlerta(tipo, marker, null, 0,
                "RegistrosAlertasPageTest", null, null);
        return findByMensaje(marker);
    }

    /** findFiltered is timestamp DESC capped at 500 — a fresh seed is first. */
    private Alertas findByMensaje(String marker) {
        return alertasService.findFiltered(null, null, null, null, null).stream()
                .filter(a -> marker.equals(a.getMensaje()))
                .findFirst()
                .orElse(null);
    }

    private void borrarPorCodigo(Integer codigo) {
        if (codigo == null) {
            return;
        }
        Alertas entidad = alertasService.find(codigo);
        if (entidad != null) {
            alertasService.delete(entidad);
        }
    }

    @Test
    void pageRendersKitMarkers() {
        Map<String, String> session = adminSession();
        authed(session)
                .when().get(PAGE_URL)
                .then()
                .statusCode(200)
                .contentType(ContentType.HTML)
                .body(containsString("<html"))
                .body(containsString("Registros Internos"))
                .body(containsString("data-kit-table"))
                .body(containsString("id=\"tabla-alertas\""))
                .body(containsString("toast-container"));
    }

    @Test
    void hxRequestReturnsFragmentWithoutLayout() {
        Map<String, String> session = adminSession();
        authed(session)
                .header("HX-Request", "true")
                .when().get(PAGE_URL)
                .then()
                .statusCode(200)
                .contentType(ContentType.HTML)
                .body(containsString("data-kit-table"))
                .body(containsString("id=\"tabla-alertas\""))
                .body(not(containsString("<html")))
                .body(not(containsString("toast-container")));
    }

    @Test
    void unreadOnlyFilterShowsSeededRowWithAckUrl() {
        Map<String, String> session = adminSession();
        String marker = "W4B T30 unread " + System.nanoTime();
        Alertas alerta = seedUnread(marker, "Info");
        assertNotNull(alerta, "seeded alert must be found by its marker mensaje");
        try {
            authed(session)
                    .header("HX-Request", "true")
                    .queryParam("unreadOnly", "true")
                    .when().get(PAGE_URL)
                    .then()
                    .statusCode(200)
                    .body(containsString(marker))
                    .body(containsString("Marcar leído"))
                    .body(containsString("/api/app/alertas/" + alerta.getCodigo()
                            + "/ack?page=1&size=20&unreadOnly=true"));
        } finally {
            borrarPorCodigo(alerta.getCodigo());
        }
    }

    @Test
    void severityBadgeUsesToastItemVocabulary() {
        Map<String, String> session = adminSession();
        String marker = "W4B T30 sev " + System.nanoTime();
        Alertas alerta = seedUnread(marker, "Error");
        assertNotNull(alerta, "seeded alert must be found by its marker mensaje");
        try {
            authed(session)
                    .header("HX-Request", "true")
                    .when().get(PAGE_URL)
                    .then()
                    .statusCode(200)
                    .body(containsString("<span class=\"tag is-danger\">Error</span>"))
                    .body(containsString(marker));
        } finally {
            borrarPorCodigo(alerta.getCodigo());
        }
    }

    @Test
    void ackFlowMarksReadAndReturnsRefreshedFragment() {
        Map<String, String> session = adminSession();
        String marker = "W4B T30 ack " + System.nanoTime();
        Alertas alerta = seedUnread(marker, "Info");
        assertNotNull(alerta, "seeded alert must be found by its marker mensaje");
        try {
            authed(session)
                    .header("HX-Request", "true")
                    .contentType(ContentType.URLENC)
                    .formParam("canal", "html")
                    .when().post(BASE + "/api/app/alertas/" + alerta.getCodigo() + "/ack")
                    .then()
                    .statusCode(200)
                    .contentType(ContentType.HTML)
                    .body(containsString("data-kit-table"))
                    .body(containsString("id=\"tabla-alertas\""))
                    .body(containsString("Leído"))
                    .body(containsString("Alerta marcada como leída"))
                    .body(containsString(marker));

            Alertas recargada = alertasService.find(alerta.getCodigo());
            assertNotNull(recargada, "acked alert must remain findable");
            assertTrue(recargada.isVista(), "ack must persist vista = true");
        } finally {
            borrarPorCodigo(alerta.getCodigo());
        }
    }

    @Test
    void ackUnknownIdAnswersFragmentWithErrorToast() {
        Map<String, String> session = adminSession();
        authed(session)
                .header("HX-Request", "true")
                .contentType(ContentType.URLENC)
                .formParam("canal", "html")
                .when().post(BASE + "/api/app/alertas/2147483000/ack")
                .then()
                .statusCode(200)
                .contentType(ContentType.HTML)
                .body(containsString("data-kit-table"))
                .body(containsString("No se encontró la alerta: 2147483000"));
    }

    @Test
    void jsonAckContractStaysGreen() {
        Map<String, String> session = adminSession();
        String marker = "W4B T30 json " + System.nanoTime();
        Alertas alerta = seedUnread(marker, "Info");
        assertNotNull(alerta, "seeded alert must be found by its marker mensaje");
        try {
            authed(session)
                    .contentType(ContentType.JSON)
                    .body("{}")
                    .when().post(BASE + "/api/app/alertas/" + alerta.getCodigo() + "/ack")
                    .then()
                    .statusCode(200)
                    .contentType(ContentType.JSON)
                    .body("error", equalTo(null))
                    .body("data.codigo", equalTo(alerta.getCodigo()));

            Alertas recargada = alertasService.find(alerta.getCodigo());
            assertNotNull(recargada);
            assertTrue(recargada.isVista(), "JSON ack must persist vista = true");
        } finally {
            borrarPorCodigo(alerta.getCodigo());
        }
    }

    @Test
    void pageBeyondLastYieldsEmptyStateNotError() {
        Map<String, String> session = adminSession();
        authed(session)
                .header("HX-Request", "true")
                .queryParam("page", 99999)
                .when().get(PAGE_URL)
                .then()
                .statusCode(200)
                .body(containsString("No se encontraron registros"));
    }

    @Test
    void anonymousPageRequestIsChallengedToLogin() {
        given().redirects().follow(false)
                .when().get(PAGE_URL)
                .then()
                .statusCode(302)
                .header("Location", containsString("/login"));
    }
}
