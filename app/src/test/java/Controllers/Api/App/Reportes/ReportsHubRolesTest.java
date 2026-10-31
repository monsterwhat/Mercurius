package Controllers.Api.App.Reportes;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

/**
 * D11 — the hub renders only the report groups/entries the current role can
 * open. The hub gate stays admin/registro/inventario/tributacion; the navbar
 * link now matches it.
 */
@QuarkusTest
@Tag("reportes-pages")
class ReportsHubRolesTest extends support.ContextPathIsolation {

    private static final String HUB = "/Mercurius/app/reportes";

    @Test
    @TestSecurity(user = "admin", roles = {"admin"})
    void adminSeesEveryGroup() {
        given().when().get(HUB).then().statusCode(200)
                .body(containsString("Reportes de Facturación"))
                .body(containsString("Facturas y correo"))
                .body(containsString("Reportes de Inventarios"))
                .body(containsString("Reportes de Usuarios"))
                .body(containsString("9 informes"))
                .body(containsString("6 informes"))
                .body(containsString("14 informes"))
                .body(containsString("3 informes"))
                .body(containsString("href=\"/Mercurius/app/facturas\""))
                .body(containsString("href=\"/Mercurius/app/articulos\""))
                .body(containsString("href=\"/Mercurius/app/reportes/usuarios\""));
    }

    @Test
    @TestSecurity(user = "inventario", roles = {"inventario"})
    void inventarioSeesStockButNotBillingOrTax() {
        given().when().get(HUB).then().statusCode(200)
                .body(containsString("Reportes de Facturación"))
                .body(containsString("Reportes de Inventarios"))
                .body(containsString("7 informes"))
                .body(containsString("13 informes"))
                .body(containsString("href=\"/Mercurius/app/reportes/articulos/fechas\""))
                .body(containsString("href=\"/Mercurius/app/articulos\""))
                .body(not(containsString("Facturas y correo")))
                .body(not(containsString("href=\"/Mercurius/app/facturas\"")))
                .body(not(containsString("href=\"/Mercurius/app/tributacion/declaracion\"")));
    }

    @Test
    @TestSecurity(user = "inventario", roles = {"inventario"})
    void movimientosIsAnInventoryReportNotASalesTab() {
        given().when().get("/Mercurius/app/reportes/articulos/fechas").then().statusCode(200)
                .body(containsString("Reporte de Movimientos de Inventario"))
                .body(not(containsString("Vistas de ventas")))
                .body(not(containsString(">Por fecha<")));
    }

    @Test
    @TestSecurity(user = "tributacion", roles = {"tributacion"})
    void tributacionSeesTaxAndMailButNotStock() {
        given().when().get(HUB).then().statusCode(200)
                .body(containsString("Facturas y correo"))
                .body(containsString("href=\"/Mercurius/app/tributacion/declaracion\""))
                .body(containsString("href=\"/Mercurius/app/correos/plantillas\""))
                .body(containsString("href=\"/Mercurius/app/reportes/estacionalidad\""))
                .body(containsString("1 informes"))
                .body(not(containsString("Reportes de Inventarios")))
                .body(not(containsString("href=\"/Mercurius/app/reportes/articulos/fechas\"")))
                .body(not(containsString("href=\"/Mercurius/app/articulos\"")));
    }

    @Test
    @TestSecurity(user = "registro", roles = {"registro"})
    void registroSeesOnlyOpenReports() {
        given().when().get(HUB).then().statusCode(200)
                .body(containsString("Reportes de Facturación"))
                .body(containsString("Reportes de Usuarios"))
                .body(containsString("href=\"/Mercurius/app/reportes/estacionalidad\""))
                .body(containsString("href=\"/Mercurius/app/reportes/clientes\""))
                .body(containsString("href=\"/Mercurius/app/reportes/loyalty\""))
                .body(containsString("1 informes"))
                .body(not(containsString("Facturas y correo")))
                .body(not(containsString("Reportes de Inventarios")))
                .body(not(containsString("href=\"/Mercurius/app/reportes/articulos/fechas\"")));
    }

    @Test
    @TestSecurity(user = "facturacion", roles = {"facturacion"})
    void facturacionStillCannotOpenTheHubButSeesNoNavbarEntry() {
        given().when().get(HUB).then().statusCode(403);
        given().when().get("/Mercurius/app/dashboard").then().statusCode(200)
                .body(not(containsString("href=\"/Mercurius/app/reportes\"")));
    }

    @Test
    @TestSecurity(user = "usuario", roles = {"usuario"})
    void usuarioStillCannotOpenTheHubButSeesNoNavbarEntry() {
        given().when().get(HUB).then().statusCode(403);
        given().when().get("/Mercurius/app/dashboard").then().statusCode(200)
                .body(not(containsString("href=\"/Mercurius/app/reportes\"")));
    }
}
