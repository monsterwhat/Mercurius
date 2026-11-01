package Controllers.Api.App.Correos;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;

/**
 * D4 role matrix of the two email management pages served by
 * {@code CorreosPagesResource} (class gate) and its per-method narrowing.
 *
 * <p>The class-level {@code @RolesAllowed} is the union
 * ({@code admin,facturacion,tributacion}) so no future method is born open;
 * {@code /plantillas} narrows to {@code admin,tributacion}. These tests pin
 * both edges, including that the class gate did not widen the templates page
 * to {@code facturacion}.</p>
 */
@QuarkusTest
@Tag("reportes-pages")
class CorreosPagesRoleGateTest extends support.ContextPathIsolation {

    private static final String REPORTES = "/Mercurius/app/correos/reportes";
    private static final String PLANTILLAS = "/Mercurius/app/correos/plantillas";

    @Test
    @TestSecurity(user = "admin", roles = {"admin"})
    void adminOpensBothPages() {
        given().when().get(REPORTES).then().statusCode(200);
        given().when().get(PLANTILLAS).then().statusCode(200);
    }

    @Test
    @TestSecurity(user = "tributacion-user", roles = {"tributacion"})
    void tributacionOpensBothPages() {
        given().when().get(REPORTES).then().statusCode(200);
        given().when().get(PLANTILLAS).then().statusCode(200);
    }

    @Test
    @TestSecurity(user = "facturador", roles = {"facturacion"})
    void facturacionReadsScheduledReportsButNotTemplates() {
        given().when().get(REPORTES).then().statusCode(200);
        given().when().get(PLANTILLAS).then().statusCode(403);
    }

    @Test
    @TestSecurity(user = "almacenero", roles = {"inventario"})
    void unrelatedRoleIsForbiddenOnBothPages() {
        given().when().get(REPORTES).then().statusCode(403);
        given().when().get(PLANTILLAS).then().statusCode(403);
    }
}
