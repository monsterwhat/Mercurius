package Controllers.Api.App.Correos;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.response.Response;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import support.AppBase;

/**
 * El botón "Cargar predeterminadas" crea el catálogo HTML de fábrica
 * (comprobantes, reportes y notificaciones) y es idempotente: no duplica las
 * que ya existen por nombre.
 */
@QuarkusTest
class EmailTemplateDefaultsTest extends AppBase {

    private static final String BASE = "/api/app/email-templates";

    private Map<String, String> csrfJar() {
        Response mint = given().when().get(BASE + "/table");
        mint.then().statusCode(200);
        return new HashMap<>(mint.getCookies());
    }

    @Test
    @TestSecurity(user = "admin", roles = {"admin", "tributacion"})
    void cargaLasPredeterminadasYNoDuplica() {
        Map<String, String> jar = csrfJar();
        int creadas = given().cookies(jar)
                .header("X-CSRF-TOKEN", jar.get("csrf-token"))
                .contentType("application/x-www-form-urlencoded")
                .when().post(BASE + "/predeterminadas")
                .then().statusCode(200)
                .extract().jsonPath().getInt("data.creadas");
        assertThat(creadas).isEqualTo(23);

        // El catálogo completo queda consultable: 4 base + 9 comprobantes + 10 reportes.
        given().cookies(jar).when().get(BASE)
                .then().statusCode(200)
                .body("total", org.hamcrest.Matchers.equalTo(23));

        // Segunda llamada: no crea ninguna más (idempotente).
        given().cookies(jar)
                .header("X-CSRF-TOKEN", jar.get("csrf-token"))
                .contentType("application/x-www-form-urlencoded")
                .when().post(BASE + "/predeterminadas")
                .then().statusCode(200)
                .body("data.creadas", org.hamcrest.Matchers.equalTo(0));
    }
}
