package Controllers.Api.App;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertNotNull;

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
 * T30 view-half acceptance suite for the Log de Actividades page served by
 * {@link LogActividadResource}: authenticated full-page GET carries the kit
 * markers and the legacy filter panel, HX-Request returns ONLY the tabla
 * fragment, the source/tipo filters narrow to a seeded row while pager and
 * sort links preserve the filter params, invalid dates and unknown users
 * answer validation toasts on a 200 fragment (never error statuses), and a
 * page beyond the last yields the empty state.
 *
 * <p>Auth mirrors AuthJourneyTest/CsrfEnforcementTest: real form-cookie login
 * over RestAssured (seed admin/admin123); GETs need no CSRF header.</p>
 */
@QuarkusTest
@Tag("w4b-views")
class RegistrosLogsPageTest extends support.ContextPathIsolation {

    private static final String BASE = "/Mercurius";
    private static final String PAGE_URL = BASE + "/api/app/logs/pagina";
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

    /** Seeds one log entry identifiable by mensaje marker + unique source. */
    private Alertas seedRegistro(String marker, String source) {
        alertasService.registrarAlerta("Info", marker, null, 0, source, null, null);
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
    void pageRendersKitMarkersAndFilterPanel() {
        Map<String, String> session = adminSession();
        authed(session)
                .when().get(PAGE_URL)
                .then()
                .statusCode(200)
                .contentType(ContentType.HTML)
                .body(containsString("<html"))
                .body(containsString("Log de Actividades"))
                .body(containsString("Auditoría de operaciones del sistema"))
                .body(containsString("data-kit-table"))
                .body(containsString("id=\"tabla-logs\""))
                .body(containsString("name=\"fechaDesde\""))
                .body(containsString("name=\"fechaHasta\""))
                .body(containsString("name=\"usuario\""))
                .body(containsString("name=\"tipo\""))
                .body(containsString("name=\"source\""))
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
                .body(containsString("id=\"tabla-logs\""))
                .body(not(containsString("<html")))
                .body(not(containsString("toast-container")));
    }

    @Test
    void filtersNarrowToSeededRowAndPreserveParams() {
        Map<String, String> session = adminSession();
        String marker = "W4B T30 log " + System.nanoTime();
        String source = "T30LOG" + System.nanoTime();
        Alertas registro = seedRegistro(marker, source);
        assertNotNull(registro, "seeded log entry must be found by its marker mensaje");
        try {
            authed(session)
                    .header("HX-Request", "true")
                    .queryParam("tipo", "Info")
                    .queryParam("source", source)
                    .when().get(PAGE_URL)
                    .then()
                    .statusCode(200)
                    .body(containsString(marker))
                    .body(containsString("Sistema"))
                    .body(containsString("&tipo=Info&source=" + source));
        } finally {
            borrarPorCodigo(registro.getCodigo());
        }
    }

    @Test
    void invalidDateYieldsValidationToastNotError() {
        Map<String, String> session = adminSession();
        authed(session)
                .header("HX-Request", "true")
                .queryParam("fechaDesde", "not-a-date")
                .when().get(PAGE_URL)
                .then()
                .statusCode(200)
                .contentType(ContentType.HTML)
                .body(containsString("Formato de fecha inválido para fechaDesde"))
                .body(containsString("No se encontraron registros con los filtros seleccionados"));
    }

    @Test
    void unknownUsuarioYieldsValidationToastNotError() {
        Map<String, String> session = adminSession();
        authed(session)
                .header("HX-Request", "true")
                .queryParam("usuario", "no_such_user_t30")
                .when().get(PAGE_URL)
                .then()
                .statusCode(200)
                .contentType(ContentType.HTML)
                .body(containsString("No se encontró el usuario: no_such_user_t30"));
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
                .body(containsString("No se encontraron registros con los filtros seleccionados"));
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
