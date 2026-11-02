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
                .body(containsString("7 informes"))
                .body(containsString("4 informes"))
                .body(containsString("6 informes"))
                .body(containsString("2 informes"))
                .body(containsString("href=\"/Mercurius/app/facturas\""))
                .body(containsString("href=\"/Mercurius/app/facturas-recibidas\""))
                .body(containsString("href=\"/Mercurius/app/correos/reportes\""));
    }

    @Test
    @TestSecurity(user = "inventario", roles = {"inventario"})
    void inventarioSeesStockButNotBillingOrTax() {
        given().when().get(HUB).then().statusCode(200)
                .body(containsString("Reportes de Facturación"))
                .body(containsString("Reportes de Inventarios"))
                .body(containsString("5 informes"))
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

    /**
     * Phase 4 (Matriz B rows 3–4): "Por familia" and "Por departamento" are
     * cuts of the sales screen, reachable from its shared tab bar. The hub must
     * carry exactly one entry for {@code rep.ventas}.
     */
    @Test
    @TestSecurity(user = "admin", roles = {"admin"})
    void salesScreenHasOneHubEntryAndItsCutsLiveInTheTabBar() {
        given().when().get(HUB).then().statusCode(200)
                .body(containsString(MENU_LINK + "/app/reportes/articulos/ventas\""))
                .body(not(containsString(MENU_LINK + "/app/reportes/articulos/familias\"")))
                .body(not(containsString(MENU_LINK + "/app/reportes/articulos/departamentos\"")));

        // The cuts are still one click away from the canonical screen.
        given().when().get("/Mercurius/app/reportes/articulos/ventas").then().statusCode(200)
                .body(containsString("href=\"/Mercurius/app/reportes/articulos/familias?"))
                .body(containsString("href=\"/Mercurius/app/reportes/articulos/departamentos?"));
    }

    /**
     * Phase 4: Resumen, Alertas and Pronósticos are three tabs of one inventory
     * screen, so the hub keeps only the canonical one and the cuts stay in the
     * tab bar. Estacionalidad is the documented exception (its gate is wider
     * than Tendencias, so it keeps its own entry for registro/tributación).
     */
    @Test
    @TestSecurity(user = "admin", roles = {"admin"})
    void inventoryCutsLiveInTheirTabBarAndEstacionalidadKeepsItsEntry() {
        given().when().get(HUB).then().statusCode(200)
                .body(containsString(MENU_LINK + "/app/reportes/inventario/resumen\""))
                .body(not(containsString(MENU_LINK + "/app/reportes/inventario/alertas\"")))
                .body(not(containsString(MENU_LINK + "/app/reportes/inventario/pronosticos\"")))
                // Rep. tendencias' cross-role cut stays reachable for every role.
                .body(containsString(MENU_LINK + "/app/reportes/estacionalidad\""));

        given().when().get("/Mercurius/app/reportes/inventario/resumen").then().statusCode(200)
                .body(containsString("href=\"/Mercurius/app/reportes/inventario/alertas?"))
                .body(containsString("href=\"/Mercurius/app/reportes/inventario/pronosticos?"));
    }

    /**
     * Criterion §10.6: the dashboard's two invoice-board buttons point at
     * {@code admin,facturacion} destinations, so only those roles may see them.
     * Every other role used to get a 403 from a visible button.
     */
    @Test
    @TestSecurity(user = "almacenero", roles = {"inventario"})
    void dashboardHidesInvoiceBoardsFromRolesThatCannotOpenThem() {
        given().when().get("/Mercurius/app/dashboard").then().statusCode(200)
                .body(not(containsString("href=\"/Mercurius/app/facturas\"")))
                .body(not(containsString("href=\"/Mercurius/app/facturas-recibidas\"")));
    }

    @Test
    @TestSecurity(user = "facturador", roles = {"facturacion"})
    void dashboardShowsInvoiceBoardsToFacturacion() {
        given().when().get("/Mercurius/app/dashboard").then().statusCode(200)
                .body(containsString("href=\"/Mercurius/app/facturas\""))
                .body(containsString("href=\"/Mercurius/app/facturas-recibidas\""));
    }

    /**
     * D7: the report and the operational board keep distinct names. "Todas las
     * Facturas" and a bare "Facturas emitidas" were the same label to a reader.
     */
    @Test
    @TestSecurity(user = "admin", roles = {"admin"})
    void emittedInvoicesUseTheD7Names() {
        given().when().get(HUB).then().statusCode(200)
                .body(containsString("Reporte de facturas emitidas"))
                .body(containsString("Facturas emitidas (gestión)"))
                .body(not(containsString(">Todas las Facturas<")));
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
