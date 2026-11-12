package Controllers.Api.App.Correos;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.response.Response;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import support.AppBase;

/**
 * Regresión del paginador de Plantillas de Correo:
 * <ul>
 *   <li>{@code modelo.size} colisionaba con {@code Map.size()} y los enlaces
 *       del pager salían con {@code size=13} (número de claves del mapa).</li>
 *   <li>El contenedor de la tabla no tenía {@code hx-boost}, así que "Página 2"
 *       hacía navegación completa al endpoint de fragmento y volvía a pintar
 *       la página 1.</li>
 * </ul>
 */
@QuarkusTest
class EmailTemplatesTablePagerTest extends AppBase {

    private static final String BASE = "/api/app/email-templates";
    private static final Pattern TR = Pattern.compile("<tr\\b");

    private Map<String, String> session() {
        Response mint = given().when().get(BASE + "/table");
        mint.then().statusCode(200);
        return new HashMap<>(mint.getCookies());
    }

    private static int rows(String html) {
        Matcher m = TR.matcher(html);
        int count = 0;
        while (m.find()) {
            count++;
        }
        return Math.max(0, count - 1); // minus the header row
    }

    @Test
    @TestSecurity(user = "admin", roles = {"admin", "tributacion"})
    void pagerKeepsPageSizeAndPageTwoShowsTheRemainder() {
        Map<String, String> cookies = session();
        String csrf = cookies.get("csrf-token");

        given().cookies(cookies)
                .header("X-CSRF-TOKEN", csrf)
                .contentType("application/x-www-form-urlencoded")
                .when().post(BASE + "/predeterminadas")
                .then().statusCode(200);

        int total = given().cookies(cookies)
                .when().get(BASE + "?page=0&size=100")
                .then().statusCode(200)
                .extract().jsonPath().getInt("total");
        assertThat(total).isGreaterThan(20);

        String firstPage = given().cookies(cookies)
                .header("HX-Request", "true")
                .when().get(BASE + "/table?page=1&size=20")
                .then().statusCode(200)
                .extract().asString();

        // El contenedor es el blanco del swap: sin hx-boost el pager navega
        // a la página completa y siempre pinta la página 1.
        assertThat(firstPage).contains("hx-boost=\"true\"")
                .contains("hx-target=\"#tabla-plantillas\"");
        assertThat(firstPage).contains("size=20")
                .doesNotContain("size=13");
        assertThat(rows(firstPage)).isEqualTo(20);

        String secondPage = given().cookies(cookies)
                .header("HX-Request", "true")
                .when().get(BASE + "/table?page=2&size=20")
                .then().statusCode(200)
                .extract().asString();

        assertThat(rows(secondPage)).isEqualTo(Math.min(20, total - 20));
        assertThat(secondPage).contains("is-current");
    }
}
