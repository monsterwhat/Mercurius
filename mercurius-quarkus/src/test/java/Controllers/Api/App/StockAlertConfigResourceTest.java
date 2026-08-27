package Controllers.Api.App;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.CoreMatchers.nullValue;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.UserTransaction;
import java.util.HashMap;
import java.util.Map;
import Models.Articulos.Articulos;
import Models.StockAlert;
import Services.ArticulosService;
import Services.StockAlertService;
import org.junit.jupiter.api.Test;

/**
 * T33 suite for {@link StockAlertConfigResource}: global engine view,
 * per-article config round-trip (JSON PUT under the rest-csrf double-submit
 * contract), validation/404 guards, triggered-alerts listing over seeded rows,
 * role gating and the config page render.
 *
 * <p>Fixtures are COMMITTED through {@link ArticulosService} /
 * {@link StockAlertService} (not {@code @TestTransaction}: the HTTP request
 * runs on a different transaction and could never see uncommitted rows) and
 * removed again in a finally block via EntityManager+UserTransaction.</p>
 */
@QuarkusTest
class StockAlertConfigResourceTest {

    private static final String BASE = "/api/app/stock-alert-config";

    @Inject
    ArticulosService articulosService;

    @Inject
    StockAlertService stockAlertService;

    @Inject
    EntityManager em;

    @Inject
    UserTransaction utx;

    // ── helpers ─────────────────────────────────────────────────────────

    /**
     * Any safe JAX-RS request mints the csrf-token cookie (rest-csrf default);
     * unsafe methods need cookie + X-CSRF-TOKEN header to match. See
     * CsrfEnforcementTest for the full enforcement matrix.
     */
    private static Map<String, String> csrfJar() {
        Response mint = given().when().get(BASE);
        mint.then().statusCode(200);
        return new HashMap<>(mint.getCookies());
    }

    private Articulos sembrarArticulo(String sufijo) {
        Articulos articulo = new Articulos();
        articulo.setNombre("IT Config Umbral " + sufijo);
        articulo.setCodigoBarra("IT-CFG-" + sufijo);
        articulo.setUnidadMedida("Unidad");
        articulo.setStatus(true);
        articulo.setProcessed(true);
        articulosService.create(articulo);
        return articulo;
    }

    private void limpiar(Long codigoArticulo, Integer idAlerta) {
        try {
            utx.begin();
            if (idAlerta != null) {
                StockAlert alerta = em.find(StockAlert.class, idAlerta);
                if (alerta != null) {
                    em.remove(alerta);
                }
            }
            if (codigoArticulo != null) {
                Articulos articulo = em.find(Articulos.class, codigoArticulo);
                if (articulo != null) {
                    em.remove(articulo);
                }
            }
            utx.commit();
        } catch (Exception e) {
            try {
                utx.rollback();
            } catch (Exception rollback) {
                rollback.printStackTrace(); // best-effort cleanup; primary failure already reported
            }
            throw new IllegalStateException("Limpieza de fixtures fallida", e);
        }
    }

    // ── scenarios ───────────────────────────────────────────────────────

    @Test
    @TestSecurity(user = "admin", roles = {"admin", "inventario"})
    void configGlobalExponeConstantesDelMotor() {
        given()
                .when().get(BASE)
                .then()
                .statusCode(200)
                .body("data.ventanaVelocidadDias", equalTo(30))
                .body("data.plazoEntregaDias", equalTo(3))
                .body("data.diasStockSeguridadPorDefecto", equalTo(7))
                .body("data.stockOptimoRespaldoDias", equalTo(14))
                .body("data.bufferReordenDias", equalTo(30))
                .body("data.articuloCodigo", nullValue());
    }

    @Test
    @TestSecurity(user = "admin", roles = {"admin", "inventario"})
    void configArticuloRoundTrip() {
        Articulos articulo = sembrarArticulo(String.valueOf(System.nanoTime()));
        Long codigo = articulo.getCodigo();
        try {
            given()
                    .queryParam("articulo", codigo)
                    .when().get(BASE)
                    .then()
                    .statusCode(200)
                    .body("data.articuloCodigo", equalTo(codigo.intValue()))
                    .body("data.diasStockSeguridad", nullValue())
                    .body("data.estadoAlertas", is(true));

            Map<String, String> jar = csrfJar();
            given()
                    .cookies(jar)
                    .header("X-CSRF-TOKEN", jar.get("csrf-token"))
                    .contentType(ContentType.JSON)
                    .body(Map.of("diasStockSeguridad", 10, "estadoAlertas", false))
                    .queryParam("articulo", codigo)
                    .when().put(BASE)
                    .then()
                    .statusCode(200)
                    .body("data.diasStockSeguridad", equalTo(10))
                    .body("data.estadoAlertas", is(false));

            given()
                    .queryParam("articulo", codigo)
                    .when().get(BASE)
                    .then()
                    .statusCode(200)
                    .body("data.diasStockSeguridad", equalTo(10))
                    .body("data.estadoAlertas", is(false));
        } finally {
            limpiar(codigo, null);
        }
    }

    @Test
    @TestSecurity(user = "admin", roles = {"admin", "inventario"})
    void putArticuloInexistente404() {
        Map<String, String> jar = csrfJar();
        given()
                .cookies(jar)
                .header("X-CSRF-TOKEN", jar.get("csrf-token"))
                .contentType(ContentType.JSON)
                .body(Map.of("diasStockSeguridad", 5))
                .queryParam("articulo", 999999999L)
                .when().put(BASE)
                .then()
                .statusCode(404)
                .body("error.code", equalTo("NOT_FOUND"))
                .body("error.message", containsString("No se encontró el artículo"));
    }

    @Test
    @TestSecurity(user = "admin", roles = {"admin", "inventario"})
    void putValidacionRechazaNegativo400() {
        Articulos articulo = sembrarArticulo(String.valueOf(System.nanoTime()));
        Long codigo = articulo.getCodigo();
        try {
            Map<String, String> jar = csrfJar();
            given()
                    .cookies(jar)
                    .header("X-CSRF-TOKEN", jar.get("csrf-token"))
                    .contentType(ContentType.JSON)
                    .body(Map.of("diasStockSeguridad", -5))
                    .queryParam("articulo", codigo)
                    .when().put(BASE)
                    .then()
                    .statusCode(400)
                    .body("error.code", equalTo("VALIDATION_ERROR"))
                    .body("error.message", containsString("no pueden ser negativos"));

            given()
                    .queryParam("articulo", codigo)
                    .when().get(BASE)
                    .then()
                    .statusCode(200)
                    .body("data.diasStockSeguridad", nullValue());
        } finally {
            limpiar(codigo, null);
        }
    }

    @Test
    @TestSecurity(user = "admin", roles = {"admin", "inventario"})
    void triggeredReflejaAlertasSembradas() {
        Articulos articulo = sembrarArticulo(String.valueOf(System.nanoTime()));
        Long codigo = articulo.getCodigo();
        StockAlert alerta = new StockAlert();
        alerta.setArticulo(articulo);
        alerta.setTipoAlerta("low_stock");
        alerta.setCantidadActual(2);
        alerta.setCantidadMinima(10);
        alerta.setSugeridoReordenar(8);
        alerta.setNotas("IT seed T33");
        stockAlertService.create(alerta);
        Integer idAlerta = alerta.getId();
        try {
            given()
                    .when().get(BASE + "/triggered")
                    .then()
                    .statusCode(200)
                    .body("data", notNullValue())
                    .body("data.findAll { it.articuloCodigo == " + codigo + " }.size()", equalTo(1))
                    .body("data.findAll { it.articuloCodigo == " + codigo + " }.tipoAlerta",
                            org.hamcrest.Matchers.hasItem("low_stock"))
                    .body("data.findAll { it.articuloCodigo == " + codigo + " }.estado",
                            org.hamcrest.Matchers.hasItem("active"))
                    .body("data.findAll { it.articuloCodigo == " + codigo + " }.cantidadActual",
                            org.hamcrest.Matchers.hasItem(2));
        } finally {
            limpiar(codigo, idAlerta);
        }
    }

    @Test
    @TestSecurity(user = "consulta", roles = {"usuario"})
    void rolSinPermiso403() {
        given()
                .when().get(BASE)
                .then()
                .statusCode(403);
    }

    @Test
    @TestSecurity(user = "admin", roles = {"admin", "inventario"})
    void paginaRenderizaKitYForma() {
        given()
                .when().get(BASE + "/pagina")
                .then()
                .statusCode(200)
                .body(containsString("data-kit-table"))
                .body(containsString("alerta-config-forma"))
                .body(containsString("Umbrales por Artículo"))
                .body(containsString("Parámetros del Motor"));
    }
}
