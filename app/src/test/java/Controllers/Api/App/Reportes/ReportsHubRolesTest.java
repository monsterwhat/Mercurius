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

    /**
     * The hub's own rows use this exact markup. Scoping to it is what keeps
     * these assertions honest: the layout's navbar carries its own links to
     * /app/articulos and /app/inventarios for the inventory role.
     */
    private static final String MENU_LINK =
            "<a class=\"menu-link py-3 px-3\" href=\"/Mercurius";

    @Test
    @TestSecurity(user = "admin", roles = {"admin"})
    void adminSeesEveryGroup() {
        given().when().get(HUB).then().statusCode(200)
                .body(containsString("Reportes de Facturación"))
                .body(containsString("Facturas y correo"))
                .body(containsString("Reportes de Inventarios"))
                .body(containsString("Reportes de Usuarios"))
                .body(containsString("9 informes"))
                .body(containsString("5 informes"))
                .body(containsString("8 informes"))
                .body(containsString("2 informes"))
                .body(containsString("href=\"/Mercurius/app/facturas\""))
                .body(containsString("href=\"/Mercurius/app/facturas-recibidas?bucket=pendientes\""))
                .body(containsString("href=\"/Mercurius/app/correos/reportes\""));
    }

    @Test
    @TestSecurity(user = "inventario", roles = {"inventario"})
    void inventarioSeesStockButNotBillingOrTax() {
        given().when().get(HUB).then().statusCode(200)
                .body(containsString("Reportes de Facturación"))
                .body(containsString("Reportes de Inventarios"))
                .body(containsString("7 informes"))
                .body(containsString("href=\"/Mercurius/app/reportes/articulos/fechas\""))
                .body(not(containsString(MENU_LINK + "/app/articulos\"")))
                .body(not(containsString("Facturas y correo")))
                .body(not(containsString("href=\"/Mercurius/app/facturas\"")))
                .body(not(containsString("href=\"/Mercurius/app/tributacion/declaracion\"")));
    }

    @Test
    @TestSecurity(user = "admin", roles = {"admin"})
    void hubNoLongerCarriesManagementScreens() {
        given().when().get(HUB).then().statusCode(200)
                .body(not(containsString(MENU_LINK + "/app/articulos\"")))
                .body(not(containsString(MENU_LINK + "/app/inventarios\"")))
                .body(not(containsString(MENU_LINK + "/app/compras/ordenes\"")))
                .body(not(containsString(MENU_LINK + "/app/reportes/inventario/etiquetas\"")))
                .body(not(containsString(MENU_LINK + "/app/categorias?tab=")))
                .body(not(containsString(MENU_LINK + "/app/categorias\"")));
    }

    /**
     * D5 — the supplier ranking is a report of its own. The hub entry must point
     * at the extracted screen, never at the Categorías management container.
     */
    @Test
    @TestSecurity(user = "admin", roles = {"admin"})
    void supplierMetricsEntryPointsAtItsOwnReport() {
        given().when().get(HUB).then().statusCode(200)
                .body(containsString(MENU_LINK + "/app/reportes/proveedores\""))
                .body(not(containsString(MENU_LINK + "/app/categorias\"")));
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
    @TestSecurity(user = "registro", roles = {"registro"})
    void registroReachesRegistrosFromAjustesNotFromTheHub() {
        given().when().get("/Mercurius/app/dashboard").then().statusCode(200)
                .body(containsString("href=\"/Mercurius/app/registros/log\""))
                .body(not(containsString("href=\"/Mercurius/app/correos/plantillas\"")));
    }

    @Test
    @TestSecurity(user = "admin", roles = {"admin"})
    void adminKeepsPlantillasInTheHubAndNoLongerInAjustes() {
        given().when().get("/Mercurius/app/dashboard").then().statusCode(200)
                .body(not(containsString("href=\"/Mercurius/app/correos/plantillas\"")));

        given().when().get(HUB).then().statusCode(200)
                .body(containsString("href=\"/Mercurius/app/correos/plantillas\""));
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
