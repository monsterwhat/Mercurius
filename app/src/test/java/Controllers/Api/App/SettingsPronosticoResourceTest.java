package Controllers.Api.App;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Contrato HTTP de los parametros economicos del pronostico: se leen de la
 * base y se editan por formulario desde Ajustes.
 */
@QuarkusTest
class SettingsPronosticoResourceTest {

    private static final String BASE = "/api/app/settings/pronostico";

    @Test
    @TestSecurity(user = "admin", roles = {"admin"})
    void lecturaDevuelveConfiguracionVigente() {
        given()
                .when().get(BASE)
                .then()
                .statusCode(200)
                .body("data.actual.nivelServicio", notNullValue())
                .body("data.actual.costoFaltante", notNullValue())
                .body("data.actual.costoMantenimiento", notNullValue())
                .body("data.actual.plazoReposicionDias", notNullValue())
                .body("data.actual.cantidadMinimaPedido", notNullValue())
                .body("data.actual.multiploPedido", notNullValue());
    }

    @Test
    @TestSecurity(user = "admin", roles = {"admin"})
    void guardadoPorFormularioCreaUnaFilaNueva() {
        String csrf = csrfToken();
        given()
                .contentType("application/x-www-form-urlencoded")
                .cookie("csrf-token", csrf)
                .formParam("csrf-token", csrf)
                .formParam("nivelServicio", "0.950")
                .formParam("costoFaltante", "10.00")
                .formParam("costoMantenimiento", "1.00")
                .formParam("plazoReposicionDias", "7")
                .formParam("cantidadMinimaPedido", "0")
                .formParam("multiploPedido", "1")
                .when().post(BASE)
                .then().statusCode(200)
                .body("data.success", org.hamcrest.CoreMatchers.is(true))
                .body("data.actual.costoFaltante", notNullValue());
        given()
                .when().get(BASE)
                .then()
                .statusCode(200)
                .body("data.actual.costoFaltante", notNullValue());
    }

    @Test
    @TestSecurity(user = "admin", roles = {"admin"})
    void nivelDeServicioFueraDeRangoSeRechaza() {
        String csrf = csrfToken();
        given()
                .contentType("application/x-www-form-urlencoded")
                .cookie("csrf-token", csrf)
                .formParam("csrf-token", csrf)
                .formParam("nivelServicio", "2")
                .formParam("costoFaltante", "10")
                .formParam("costoMantenimiento", "1")
                .formParam("plazoReposicionDias", "7")
                .formParam("cantidadMinimaPedido", "0")
                .formParam("multiploPedido", "1")
                .when().post(BASE)
                .then()
                .statusCode(400);
    }

    /** Token double-submit emitido por el GET seguro del propio endpoint. */
    private static String csrfToken() {
        Response pagina = given().when().get(BASE);
        pagina.then().statusCode(200);
        String token = pagina.getCookie("csrf-token");
        assertNotNull(token, "el GET seguro debe emitir la cookie csrf-token");
        return token;
    }
}
