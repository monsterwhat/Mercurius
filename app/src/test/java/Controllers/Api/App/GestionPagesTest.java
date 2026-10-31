package Controllers.Api.App;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

/**
 * D13/D6 phase 1: the Gestión landing and the real purchase-orders page.
 * Each card is role-gated, so the landing never links to a screen the role
 * cannot open.
 */
@QuarkusTest
@Tag("gestion-pages")
class GestionPagesTest extends support.ContextPathIsolation {

    private static final String GESTION = "/Mercurius/app/gestion";
    private static final String ORDENES = "/Mercurius/app/compras/ordenes";

    @Test
    @TestSecurity(user = "admin", roles = {"admin"})
    void adminSeesManagementCardsAndTheOrdersPage() {
        given().when().get(GESTION).then().statusCode(200)
                .body(containsString("href=\"/Mercurius/app/articulos\""))
                .body(containsString("href=\"/Mercurius/app/compras/ordenes\""))
                .body(containsString("href=\"/Mercurius/app/usuarios\""))
                .body(containsString("href=\"/Mercurius/app/reportes/clientes\""));

        given().when().get(ORDENES).then().statusCode(200)
                .body(containsString("Órdenes de Compra"))
                .body(containsString("data-kit-table"));

        given().when().get("/Mercurius/app/dashboard").then().statusCode(200)
                .body(containsString("href=\"/Mercurius/app/gestion\""));
    }

    @Test
    @TestSecurity(user = "almacenero", roles = {"inventario"})
    void inventarioSeesStockCardsAndOrdersButNotUsers() {
        given().when().get(GESTION).then().statusCode(200)
                .body(containsString("Órdenes de compra"))
                .body(containsString("href=\"/Mercurius/app/inventarios\""))
                .body(not(containsString("href=\"/Mercurius/app/usuarios\"")))
                .body(not(containsString("href=\"/Mercurius/app/correos/plantillas\"")));

        given().when().get(ORDENES).then().statusCode(200)
                .body(containsString("Órdenes de Compra"));
    }

    @Test
    @TestSecurity(user = "tributacion", roles = {"tributacion"})
    void tributacionSeesMailCardsButNotStockOrOrders() {
        given().when().get(GESTION).then().statusCode(200)
                .body(containsString("href=\"/Mercurius/app/correos/plantillas\""))
                .body(containsString("href=\"/Mercurius/app/correos/reportes\""))
                .body(not(containsString("Órdenes de compra")))
                .body(not(containsString("href=\"/Mercurius/app/articulos\"")));

        given().when().get(ORDENES).then().statusCode(403);
    }

    @Test
    @TestSecurity(user = "facturador", roles = {"facturacion"})
    void facturacionCannotOpenGestion() {
        given().when().get(GESTION).then().statusCode(403);
        given().when().get(ORDENES).then().statusCode(403);
    }

    @Test
    @TestSecurity(user = "usuario", roles = {"usuario"})
    void usuarioCannotOpenGestion() {
        given().when().get(GESTION).then().statusCode(403);
    }
}
