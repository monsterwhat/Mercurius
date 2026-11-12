package Controllers.Api.App;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import support.AppBase;

/**
 * Contrato de los modificadores de lealtad por cliente:
 * {@code loyaltyMultiplier} (multiplicador de puntos) y
 * {@code cashbackOverride} (porcentaje propio).
 *
 * <p>Cubre el alta por JSON, el borrado explícito con
 * {@code clearLoyaltyMultiplier}/{@code clearCashbackOverride}, la edición por
 * el formulario HTMX (campos vacíos borran el override) y el rechazo de
 * valores fuera de rango.</p>
 */
@QuarkusTest
class ClientsLoyaltyModifierTest extends AppBase {

    private static final String BASE = "/api/app/clientes";
    private static final String CSRF_COOKIE = "csrf-token";
    private static final String CSRF_HEADER = "X-CSRF-TOKEN";

    private Map<String, String> session() {
        Response mint = given().when().get(BASE);
        mint.then().statusCode(200);
        return new HashMap<>(mint.getCookies());
    }

    private static io.restassured.specification.RequestSpecification authed(Map<String, String> cookies) {
        return given().cookies(cookies).header(CSRF_HEADER, cookies.get(CSRF_COOKIE));
    }

    private static BigDecimal decimal(Response response, String field) {
        Object raw = response.jsonPath().get("data." + field);
        return raw == null ? null : new BigDecimal(raw.toString());
    }

    @Test
    @TestSecurity(user = "admin", roles = {"admin", "tributacion"})
    void createUpdateAndClearModifiers() {
        Map<String, String> cookies = session();

        Map<String, Object> payload = new HashMap<>();
        payload.put("name", "Cliente Lealtad QA");
        payload.put("email", "lealtad.qa@example.com");
        payload.put("zoneCode", 0);
        payload.put("taxpayer", false);
        payload.put("loyaltyMultiplier", 2);
        payload.put("cashbackOverride", 8);

        Response created = authed(cookies)
                .contentType(ContentType.JSON)
                .body(payload)
                .when().post(BASE)
                .then().statusCode(201)
                .extract().response();
        int code = created.jsonPath().getInt("data.code");
        assertThat(decimal(created, "loyaltyMultiplier")).isEqualByComparingTo("2");
        assertThat(decimal(created, "cashbackOverride")).isEqualByComparingTo("8");

        // Persistido: el GET devuelve los mismos valores.
        Response fetched = authed(cookies).when().get(BASE + "/" + code)
                .then().statusCode(200).extract().response();
        assertThat(decimal(fetched, "loyaltyMultiplier")).isEqualByComparingTo("2");
        assertThat(decimal(fetched, "cashbackOverride")).isEqualByComparingTo("8");

        // Borrado explícito desde el JSON (null = no tocar; los flags borran).
        Map<String, Object> clear = new HashMap<>();
        clear.put("clearLoyaltyMultiplier", true);
        clear.put("clearCashbackOverride", true);
        Response cleared = authed(cookies)
                .contentType(ContentType.JSON)
                .body(clear)
                .when().put(BASE + "/" + code)
                .then().statusCode(200)
                .extract().response();
        assertThat(decimal(cleared, "loyaltyMultiplier")).isNull();
        assertThat(decimal(cleared, "cashbackOverride")).isNull();
    }

    @Test
    @TestSecurity(user = "admin", roles = {"admin", "tributacion"})
    void formUpdateSetsAndBlankClearsModifiers() {
        Map<String, String> cookies = session();

        Response created = authed(cookies)
                .contentType(ContentType.URLENC)
                .formParam("name", "Cliente Lealtad Form QA")
                .formParam("email", "lealtad.form@example.com")
                .formParam("zoneCode", "0")
                .when().post(BASE)
                .then().statusCode(201)
                .extract().response();
        int code = created.jsonPath().getInt("data.code");

        // El formulario guarda ambos modificadores.
        authed(cookies)
                .contentType(ContentType.URLENC)
                .formParam("name", "Cliente Lealtad Form QA")
                .formParam("email", "lealtad.form@example.com")
                .formParam("zoneCode", "0")
                .formParam("loyaltyMultiplier", "1.5")
                .formParam("cashbackOverride", "3.25")
                .when().put(BASE + "/" + code)
                .then().statusCode(200);

        Response withValues = authed(cookies).when().get(BASE + "/" + code)
                .then().statusCode(200).extract().response();
        assertThat(decimal(withValues, "loyaltyMultiplier")).isEqualByComparingTo("1.5");
        assertThat(decimal(withValues, "cashbackOverride")).isEqualByComparingTo("3.25");

        // Campos vacíos en el formulario = quitar el override.
        authed(cookies)
                .contentType(ContentType.URLENC)
                .formParam("name", "Cliente Lealtad Form QA")
                .formParam("email", "lealtad.form@example.com")
                .formParam("zoneCode", "0")
                .formParam("loyaltyMultiplier", "")
                .formParam("cashbackOverride", "")
                .when().put(BASE + "/" + code)
                .then().statusCode(200);

        Response cleared = authed(cookies).when().get(BASE + "/" + code)
                .then().statusCode(200).extract().response();
        assertThat(decimal(cleared, "loyaltyMultiplier")).isNull();
        assertThat(decimal(cleared, "cashbackOverride")).isNull();
    }

    @Test
    @TestSecurity(user = "admin", roles = {"admin", "tributacion"})
    void rejectsOutOfRangeModifiers() {
        Map<String, String> cookies = session();

        Map<String, Object> payload = new HashMap<>();
        payload.put("name", "Cliente Lealtad Invalida QA");
        payload.put("zoneCode", 0);
        payload.put("taxpayer", false);
        payload.put("loyaltyMultiplier", 0);

        authed(cookies)
                .contentType(ContentType.JSON)
                .body(payload)
                .when().post(BASE)
                .then().statusCode(400)
                .body("error.code", org.hamcrest.Matchers.equalTo("VALIDATION_ERROR"));

        payload.put("loyaltyMultiplier", 2);
        payload.put("cashbackOverride", 101);
        authed(cookies)
                .contentType(ContentType.JSON)
                .body(payload)
                .when().post(BASE)
                .then().statusCode(400)
                .body("error.code", org.hamcrest.Matchers.equalTo("VALIDATION_ERROR"));
    }
}
