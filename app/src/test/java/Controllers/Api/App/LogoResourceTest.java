package Controllers.Api.App;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;
import support.AppBase;

/**
 * El logo de la empresa se sirve en una ruta pública {@code /logo} para que el
 * login (sin sesión) y el sidebar muestren siempre el logo subido, no un PNG
 * fijo empaquetado. Sin sesión debe responder 200 con imagen y {@code no-store}.
 */
@QuarkusTest
class LogoResourceTest extends AppBase {

    @Test
    void sirveElLogoDeFabricaSinSesionYNoLoCachea() {
        Response r = given().when().get("/logo");
        r.then().statusCode(200);
        assertThat(r.contentType()).startsWith("image/");
        assertThat(r.header("Cache-Control")).isEqualTo("no-store");
        assertThat(r.asByteArray().length).isGreaterThan(100);
    }
}
