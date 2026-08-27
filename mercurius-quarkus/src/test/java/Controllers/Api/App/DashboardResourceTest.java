package Controllers.Api.App;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.CoreMatchers.not;

/**
 * T29 acceptance suite for {@link DashboardResource} (Dashboard + Chart.js swap):
 * authenticated page renders the two chart canvases + KPI tiles, JSON feeds keep
 * their schema (hourly=24 slots, weekly=7 slots, kpi ApiResponse envelope),
 * anonymous requests are redirected to login, and role gating mirrors the legacy
 * page (charts/KPIs for admin|facturacion only).
 */
@QuarkusTest
class DashboardResourceTest {

    private static final String PAGE = "/app/dashboard";

    // ── Page ──

    @Test
    @TestSecurity(user = "admin", roles = {"admin", "facturacion"})
    void paginaAutenticadaContieneCanvasYKpis() {
        given()
                .when().get(PAGE)
                .then()
                .statusCode(200)
                .body(containsString("grafica-ventas-hora"))
                .body(containsString("grafica-tendencia-semanal"))
                .body(containsString("Ventas Hoy"))
                .body(containsString("Ticket Promedio"))
                .body(containsString("type=\"module\""))
                .body(containsString("window.Chart"));
    }

    @Test
    @TestSecurity(user = "admin", roles = {"admin", "facturacion", "inventario"})
    void paginaAdminIncluyeRespaldoJsonYTopProductos() {
        given()
                .when().get(PAGE)
                .then()
                .statusCode(200)
                .body(containsString("datos-respaldo-horas"))
                .body(containsString("datos-respaldo-semana"))
                .body(containsString("Productos Más Vendidos"));
    }

    @Test
    @TestSecurity(user = "cajero", roles = {"usuario"})
    void cajeroVeResumenBasicoSinGraficas() {
        given()
                .when().get(PAGE)
                .then()
                .statusCode(200)
                .body(containsString("Resumen de Hoy"))
                .body(containsString("Última Transacción"))
                .body(not(containsString("grafica-ventas-hora")));
    }

    @Test
    void anonimoRecibe302() {
        given()
                .redirects().follow(false)
                .when().get(PAGE)
                .then()
                .statusCode(302);
    }

    // ── JSON feeds ──

    @Test
    @TestSecurity(user = "admin", roles = {"admin", "facturacion"})
    void datosHorariosSon24RegistrosConEsquemaEstable() {
        given()
                .when().get(PAGE + "/data/hourly")
                .then()
                .statusCode(200)
                .body("size()", is(24))
                .body("[0].hora", is("00:00"))
                .body("[23].hora", is("23:00"))
                .body("[0].ventas", notNullValue())
                .body("[0].transacciones", notNullValue());
    }

    @Test
    @TestSecurity(user = "admin", roles = {"admin", "facturacion"})
    void datosSemanalesSon7RegistrosConEsquemaEstable() {
        given()
                .when().get(PAGE + "/data/weekly")
                .then()
                .statusCode(200)
                .body("size()", is(7))
                .body("[0].fecha", notNullValue())
                .body("[0].dia", notNullValue())
                .body("[0].ventas", notNullValue())
                .body("[0].transacciones", notNullValue());
    }

    @Test
    @TestSecurity(user = "admin", roles = {"admin", "facturacion"})
    void datosKpiEnvueltosEnApiResponse() {
        given()
                .when().get(PAGE + "/data/kpi")
                .then()
                .statusCode(200)
                .body("data.ventasHoy", notNullValue())
                .body("data.ventasAyer", notNullValue())
                .body("data.transaccionesHoy", notNullValue())
                .body("data.articulosVendidos", notNullValue())
                .body("error", nullValue());
    }

    @Test
    @TestSecurity(user = "cajero", roles = {"usuario"})
    void feedsAlcanzablesParaRolBasico() {
        given()
                .when().get(PAGE + "/data/hourly")
                .then()
                .statusCode(200)
                .body("size()", is(24));
        given()
                .when().get(PAGE + "/data/weekly")
                .then()
                .statusCode(200)
                .body("size()", org.hamcrest.Matchers.anyOf(is(7), is(0)));
    }

    @Test
    void feedsAnonimosReciben302() {
        given()
                .redirects().follow(false)
                .when().get(PAGE + "/data/kpi")
                .then()
                .statusCode(302);
    }
}
