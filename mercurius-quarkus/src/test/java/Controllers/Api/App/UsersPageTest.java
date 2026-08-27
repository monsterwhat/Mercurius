package Controllers.Api.App;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import java.util.HashMap;
import java.util.Map;
import Models.Users;
import Services.LoginService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * W4B view-half acceptance suite for {@link UsersResource}: authenticated
 * page GET carries the kit markers, HX-Request returns ONLY the table
 * fragment, the pre-existing JSON CRUD contract stays green, the HTMX form
 * twin creates through the same admin-only guard + duplicate-username 409,
 * and hx-delete answers with the refreshed table fragment.
 *
 * <p>Auth mirrors CategoriaResourceTest: real form-cookie login over
 * RestAssured (seed admin/admin123) + {@code X-CSRF-TOKEN} dance;
 * {@code @TestSecurity} where a non-admin role matrix case is simpler.</p>
 */
@QuarkusTest
@Tag("w4b-views")
class UsersPageTest extends support.ContextPathIsolation {

    private static final String BASE = "/Mercurius";
    private static final String TABLE_URL = BASE + "/api/app/users/table";
    private static final String CSRF_COOKIE = "csrf-token";
    private static final String CSRF_HEADER = "X-CSRF-TOKEN";

    @Inject
    LoginService loginService;

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

    /** Anonymous visitor cookies: enough to satisfy the CSRF filter. */
    private static Map<String, String> anonymousCookies() {
        Response loginPage = given().redirects().follow(false)
                .when().get(BASE + "/login");
        loginPage.then().statusCode(200);
        return new HashMap<>(loginPage.getCookies());
    }

    private static RequestSpecification authed(Map<String, String> cookies) {
        RequestSpecification spec = given().redirects().follow(false).cookies(cookies);
        String token = cookies.get(CSRF_COOKIE);
        if (token != null) {
            spec.header(CSRF_HEADER, token);
        }
        return spec;
    }

    @Test
    void pageRendersKitMarkersAndStatCards() {
        Map<String, String> session = adminSession();
        authed(session)
                .when().get(TABLE_URL)
                .then()
                .statusCode(200)
                .contentType(ContentType.HTML)
                .body(containsString("<html"))
                .body(containsString("Usuarios del Sistema"))
                .body(containsString("data-kit-table"))
                .body(containsString("id=\"tabla-usuarios\""))
                .body(containsString("toast-container"));
    }

    @Test
    void hxRequestReturnsFragmentWithoutLayout() {
        Map<String, String> session = adminSession();
        authed(session)
                .header("HX-Request", "true")
                .when().get(TABLE_URL)
                .then()
                .statusCode(200)
                .contentType(ContentType.HTML)
                .body(containsString("data-kit-table"))
                .body(containsString("id=\"tabla-usuarios\""))
                .body(not(containsString("<html")))
                .body(not(containsString("toast-container")));
    }

    @Test
    void searchFilterRoundTripFindsSeededAdmin() {
        Map<String, String> session = adminSession();
        authed(session)
                .header("HX-Request", "true")
                .queryParam("q", "admin")
                .when().get(TABLE_URL)
                .then()
                .statusCode(200)
                .body(containsString("admin"));
    }

    @Test
    void jsonCrudRoundTripStaysGreen() {
        Map<String, String> session = adminSession();
        String marker = "w4b-json-" + System.nanoTime();

        authed(session)
                .contentType(ContentType.JSON)
                .body(Map.of("username", marker, "password", "Secreto1!", "groupName", "[usuario]"))
                .when().post(BASE + "/api/app/users")
                .then()
                .statusCode(201)
                .body("data.username", equalTo(marker));

        Users creado = loginService.findByUsername(marker);
        assertNotNull(creado, "the JSON create must persist the user");

        authed(session)
                .contentType(ContentType.JSON)
                .body(Map.of("email", "w4b@mercurius.local"))
                .when().put(BASE + "/api/app/users/" + creado.getId())
                .then()
                .statusCode(200)
                .body("data.email", equalTo("w4b@mercurius.local"));

        authed(session)
                .when().delete(BASE + "/api/app/users/" + creado.getId())
                .then()
                .statusCode(200)
                .body("data.status", equalTo(false));

        Users archived = loginService.find(creado.getId());
        assertNotNull(archived);
        assertFalse(archived.getStatus(), "DELETE must soft-disable the user");

        loginService.delete(archived);
    }

    @Test
    void duplicateUsernameStillSurfaces409OnJsonContract() {
        Map<String, String> session = adminSession();
        authed(session)
                .contentType(ContentType.JSON)
                .body(Map.of("username", "admin", "password", "x", "groupName", "[admin]"))
                .when().post(BASE + "/api/app/users")
                .then()
                .statusCode(409)
                .body("error.code", equalTo("USERNAME_TAKEN"));
    }

    @Test
    void formTwinCreateRedirectsAndPersists() {
        Map<String, String> session = adminSession();
        String marker = "w4b-form-" + System.nanoTime();
        try {
            authed(session)
                    .header("HX-Request", "true")
                    .contentType(ContentType.URLENC)
                    .formParam("username", marker)
                    .formParam("password", "Secreto1!")
                    .formParam("groupName", "usuario")
                    .formParam("groupName", "inventario")
                    .when().post(BASE + "/api/app/users")
                    .then()
                    .statusCode(200)
                    .header("HX-Redirect", equalTo("/api/app/users/table"));

            Users creado = loginService.findByUsername(marker);
            assertNotNull(creado, "the HTMX form twin must persist the user");
            assertFalse(creado.getGroupName().isEmpty());
            loginService.delete(creado);
        } finally {
            Users leftover = loginService.findByUsername(marker);
            if (leftover != null) {
                loginService.delete(leftover);
            }
        }
    }

    @Test
    @TestSecurity(user = "invitado", roles = "usuario")
    void testSecurityUsuarioRoleIsForbiddenFromCreateForm() {
        Map<String, String> cookies = anonymousCookies();
        authed(cookies)
                .contentType(ContentType.URLENC)
                .formParam("username", "w4b-prohibido")
                .formParam("password", "Secreto1!")
                .formParam("groupName", "usuario")
                .when().post(BASE + "/api/app/users")
                .then()
                .statusCode(403);
    }

    @Test
    void hxDeleteReturnsRefreshedTableFragment() {
        Map<String, String> session = adminSession();
        String marker = "w4b-hxdel-" + System.nanoTime();

        authed(session)
                .contentType(ContentType.JSON)
                .body(Map.of("username", marker, "password", "Secreto1!", "groupName", "[usuario]"))
                .when().post(BASE + "/api/app/users")
                .then()
                .statusCode(201);

        Users creado = loginService.findByUsername(marker);
        assertNotNull(creado);
        try {
            authed(session)
                    .header("HX-Request", "true")
                    .when().delete(BASE + "/api/app/users/" + creado.getId())
                    .then()
                    .statusCode(200)
                    .contentType(ContentType.HTML)
                    .body(containsString("data-kit-table"))
                    .body(containsString("hx-swap-oob"))
                    .body(containsString(marker));
        } finally {
            Users archived = loginService.find(creado.getId());
            loginService.delete(archived != null ? archived : creado);
        }
    }
}
