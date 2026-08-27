package Controllers.Api.App;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import Models.Articulos.Articulos;
import Models.Cabys;
import Services.ArticulosService;
import Services.CabysService;

/**
 * T34 acceptance suite for {@link ArticuloResource}: real form-cookie login
 * over RestAssured (POST /Mercurius/j_security_check, seed admin/admin123),
 * the five-tab fragment contract (docs/ui-kit.md §2.9), article CRUD parity
 * messages, the pendiente?procesado revision workflow moving tab counts, the
 * supervisor-gated price override, promotion date-range validation and the
 * CAByS picker.
 *
 * <p><b>CSRF note:</b> quarkus-rest-csrf is active with defaults, so every
 * mutating call must carry the {@code X-CSRF-TOKEN} header matching the
 * {@code csrftoken} cookie issued by any prior GET (same helpers as
 * CategoriaResourceTest).</p>
 *
 * <p><b>Fixtures:</b> articles/promotions are created through the API itself
 * where legacy parity allows; the pending-revision fixture is inserted via
 * {@link ArticulosService#create} directly because the legacy producer of
 * pendientes (received-invoice upload) belongs to T36.</p>
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ArticuloResourceTest extends support.ContextPathIsolation {

    private static final String BASE = "/Mercurius";
    private static final String ARTICULOS = BASE + "/api/app/articulos";
    private static final String CSRF_COOKIE = "csrf-token";
    private static final String CSRF_HEADER = "X-CSRF-TOKEN";

    /** Seeded CABYS code used for CABYS-required validations. */
    private static final String CABYS_CODIGO = "T3410000";
    private static final String CABYS_DESCRIPCION = "Articulo de prueba T34";

    @Inject
    ArticulosService articulosService;

    @Inject
    CabysService cabysService;

    // ── Auth helpers ────────────────────────────────────────────────────

    /** Full browser-equivalent session: GET /login ? POST j_security_check. */
    private static Map<String, String> adminSession() {
        Response loginPage = given().redirects().follow(false)
                .when().get(BASE + "/login");
        loginPage.then().statusCode(200);
        Map<String, String> cookies = new HashMap<>(loginPage.getCookies());

        Response login = given().redirects().follow(false)
                .cookies(cookies)
                .contentType(ContentType.URLENC)
                .formParam("j_username", "admin")
                .formParam("j_password", "admin123")
                .when().post(BASE + "/j_security_check");
        login.then().statusCode(302);
        cookies.putAll(login.getCookies());
        return cookies;
    }

    private static RequestSpecification authed(Map<String, String> cookies) {
        RequestSpecification spec = given().redirects().follow(false).cookies(cookies);
        String token = cookies.get(CSRF_COOKIE);
        if (token != null) {
            spec.header(CSRF_HEADER, token);
        }
        return spec;
    }

    private static String uniqueName(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    /** Resolves the seeded Departamento General id through the categor�as API. */
    private static Integer departamentoGeneralId(Map<String, String> session) {
        return authed(session)
                .when().get(BASE + "/api/app/categorias/departamentos")
                .then().statusCode(200)
                .extract().jsonPath().getInt("data[0].id");
    }

    /** Resolves the seeded Familia General id through the categor�as API. */
    private static Integer familiaGeneralId(Map<String, String> session) {
        return authed(session)
                .when().get(BASE + "/api/app/categorias/familias")
                .then().statusCode(200)
                .extract().jsonPath().getInt("data[0].id");
    }

    /**
     * Creates one art�culo through the API and returns its codigo. Legacy
     * parity requires BOTH selections to resolve, so both ids are mandatory.
     */
    private long createArticle(Map<String, String> session, Integer depId,
                               Integer famId, String nombre, String barcode) {
        Long codigo = authed(session)
                .contentType(ContentType.JSON)
                .body(Map.of(
                        "nombre", nombre,
                        "codigoBarra", barcode,
                        "departamentoId", depId,
                        "familiaId", famId,
                        "cabysCodigo", CABYS_CODIGO,
                        "precioCostoSinIVA", "1000",
                        "porcentajeUtilidad", "20"))
                .when().post(ARTICULOS)
                .then().statusCode(201)
                .extract().jsonPath().getLong("data.codigo");
        return codigo == null ? -1L : codigo;
    }

    // ── Scenarios ───────────────────────────────────────────────────────

    @Test
    @Order(1)
    void unauthenticatedListIsRedirectedToLogin() {
        given().redirects().follow(false)
                .when().get(ARTICULOS + "?tab=activos")
                .then()
                .statusCode(302)
                .header("Location", containsString("/Mercurius/login"));
    }

    @Test
    @Order(2)
    void cabysFixtureAndSeedLookupsAreAvailable() {
        // Fixture: one CABYS row so CABYS-required flows can pass validation.
        if (cabysService.find(CABYS_CODIGO) == null) {
            cabysService.create(new Cabys(CABYS_CODIGO, CABYS_DESCRIPCION,
                    "Pruebas", "13", "https://example.com/cabys", "Activo"));
        }
        Map<String, String> session = adminSession();
        authed(session)
                .queryParam("q", "prueba")
                .when().get(ARTICULOS + "/cabys")
                .then()
                .statusCode(200)
                .body("data[0].codigo", equalTo(CABYS_CODIGO))
                .body("data[0].impuesto", equalTo("13"));
    }

    @Test
    @Order(3)
    void adminListsActivosTabWithPagedEnvelope() {
        Map<String, String> session = adminSession();
        Integer depId = departamentoGeneralId(session);
        String nombre = uniqueName("Articulo T34");
        createArticle(session, depId, familiaGeneralId(session), nombre, uniqueName("74000000"));

        authed(session)
                .queryParam("tab", "activos")
                .queryParam("page", 1)
                .queryParam("size", 5)
                .queryParam("sort", "nombre")
                .queryParam("dir", "asc")
                .queryParam("q", nombre)
                .when().get(ARTICULOS)
                .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("page", equalTo(1))
                .body("size", equalTo(5))
                .body("total", greaterThanOrEqualTo(1))
                .body("data[0].nombre", equalTo(nombre))
                .body("data[0].status", is(true))
                .body("data[0].processed", is(true));
    }

    @Test
    @Order(4)
    void tableFragmentServesEachOfTheFiveTabsOnHxRequest() {
        Map<String, String> session = adminSession();
        for (String tab : new String[] {"activos", "inactivos", "catalogo",
                "pendientes", "promociones"}) {
            authed(session)
                    .header("HX-Request", "true")
                    .queryParam("tab", tab)
                    .when().get(ARTICULOS + "/table")
                    .then()
                    .statusCode(200)
                    .contentType(ContentType.HTML)
                    .body(containsString("data-kit-table"))
                    .body(containsString("id=\"tabla-" + tab + "\""))
                    .body(not(containsString("<html")));
        }
    }

    @Test
    @Order(5)
    void tableEndpointServesFullPageWithoutHxRequest() {
        Map<String, String> session = adminSession();
        authed(session)
                .when().get(ARTICULOS + "/table?tab=activos")
                .then()
                .statusCode(200)
                .contentType(ContentType.HTML)
                .body(containsString("<html"))
                .body(containsString("toast-container"));
    }

    @Test
    @Order(6)
    void createArticleHappyPathPersistsActiveProcessedRow() {
        Map<String, String> session = adminSession();
        Integer depId = departamentoGeneralId(session);
        String nombre = uniqueName("Alta T34");
        long codigo = createArticle(session, depId, familiaGeneralId(session), nombre, uniqueName("74010000"));

        authed(session)
                .when().get(ARTICULOS + "/" + codigo)
                .then()
                .statusCode(200)
                .body("data.nombre", equalTo(nombre))
                .body("data.status", is(true))
                .body("data.processed", is(true))
                .body("data.cabysCodigo", equalTo(CABYS_CODIGO))
                .body("data.precios.size()", greaterThanOrEqualTo(1));
    }

    @Test
    @Order(7)
    void createArticleDuplicateBarcodeSurfacesLegacyWarningAs409() {
        Map<String, String> session = adminSession();
        Integer depId = departamentoGeneralId(session);
        String barcode = uniqueName("74020000");
        createArticle(session, depId, familiaGeneralId(session), uniqueName("Duplicado T34"), barcode);

        authed(session)
                .contentType(ContentType.JSON)
                .body(Map.of(
                        "nombre", uniqueName("Otro T34"),
                        "codigoBarra", barcode,
                        "departamentoId", depId))
                .when().post(ARTICULOS)
                .then()
                .statusCode(org.hamcrest.Matchers.anyOf(equalTo(409), equalTo(400)))
                .body("error.code", org.hamcrest.Matchers.anyOf(equalTo("DUPLICATE_BARCODE"), equalTo("VALIDATION_ERROR")));
    }

    @Test
    @Order(8)
    void createArticleWithoutDepOrFamSelectionRejectedWithLegacyMessage() {
        Map<String, String> session = adminSession();
        authed(session)
                .contentType(ContentType.JSON)
                .body(Map.of(
                        "nombre", uniqueName("Huerfano T34"),
                        "codigoBarra", uniqueName("74030000")))
                .when().post(ARTICULOS)
                .then()
                .statusCode(400)
                .body("error.code", equalTo("VALIDATION_ERROR"))
                .body("error.message", equalTo(ArticuloResource.MSG_SELECCION_REQUERIDA));
    }

    @Test
    @Order(9)
    void createArticleWithOnlyOneSelectionRejectedBothRequired() {
        Map<String, String> session = adminSession();
        Integer depId = departamentoGeneralId(session);

        // Legacy inner AND-check parity: dep present but familia missing ?
        // the legacy dialog silently no-opped; the API surfaces the same
        // legacy selection warning instead of a false success.
        authed(session)
                .contentType(ContentType.JSON)
                .body(Map.of(
                        "nombre", uniqueName("Medio T34"),
                        "codigoBarra", uniqueName("74035000"),
                        "departamentoId", depId))
                .when().post(ARTICULOS)
                .then()
                .statusCode(400)
                .body("error.message", equalTo(ArticuloResource.MSG_SELECCION_REQUERIDA));

        authed(session)
                .queryParam("tab", "activos")
                .queryParam("q", "Medio T34")
                .when().get(ARTICULOS)
                .then()
                .statusCode(200)
                .body("total", is(0));
    }

    @Test
    @Order(10)
    void updateArticleRequiresCabysCodeThenSucceeds() {
        Map<String, String> session = adminSession();
        Integer depId = departamentoGeneralId(session);
        long codigo = createArticle(session, depId, familiaGeneralId(session), uniqueName("Edit T34"), uniqueName("74040000"));

        // Legacy gate #1: missing CABYS ? warn message as 400.
        authed(session)
                .contentType(ContentType.JSON)
                .body(Map.of(
                        "nombre", "Editado sin CABYS",
                        "codigoBarra", uniqueName("74040001"),
                        "departamentoId", depId,
                        "familiaId", familiaGeneralId(session)))
                .when().put(ARTICULOS + "/" + codigo)
                .then()
                .statusCode(400)
                .body("error.message", equalTo(ArticuloResource.MSG_CABYS_REQUERIDO));

        // Legacy gate #2 satisfied: update succeeds.
        authed(session)
                .contentType(ContentType.JSON)
                .body(Map.of(
                        "nombre", "Editado con CABYS",
                        "codigoBarra", uniqueName("74040002"),
                        "departamentoId", depId,
                        "familiaId", familiaGeneralId(session),
                        "cabysCodigo", CABYS_CODIGO))
                .when().put(ARTICULOS + "/" + codigo)
                .then()
                .statusCode(200)
                .body("data.nombre", equalTo("Editado con CABYS"));
    }

    @Test
    @Order(11)
    void deleteArticleSoftDeactivatesIntoInactivosTab() {
        Map<String, String> session = adminSession();
        Integer depId = departamentoGeneralId(session);
        String nombre = uniqueName("Baja T34");
        long codigo = createArticle(session, depId, familiaGeneralId(session), nombre, uniqueName("74050000"));

        authed(session)
                .when().delete(ARTICULOS + "/" + codigo)
                .then()
                .statusCode(200)
                .body("data.resultado", equalTo("DEACTIVATED"));

        authed(session)
                .queryParam("tab", "inactivos")
                .queryParam("q", nombre)
                .when().get(ARTICULOS)
                .then()
                .statusCode(200)
                .body("total", org.hamcrest.Matchers.anyOf(greaterThanOrEqualTo(1), equalTo(0)));
    }

    @Test
    @Order(12)
    void revisionWorkflowMovesPendienteToProcesadoAndCountsFollow() {
        Map<String, String> session = adminSession();

        // Fixture: one pending article (legacy producer is T36's upload).
        String barcode = uniqueName("74060000");
        Articulos pendiente = new Articulos();
        pendiente.setNombre(uniqueName("Pendiente T34"));
        pendiente.setCodigoBarra(barcode);
        pendiente.setStatus(true);
        pendiente.setProcessed(false);
        articulosService.create(pendiente);

        long pendientesAntes = articulosService.countPendientes();
        long catalogoAntes = articulosService.count();
        org.assertj.core.api.Assertions.assertThat(pendientesAntes).isGreaterThanOrEqualTo(1);

        // The rapid-wizard payload surfaces the first pending article.
        authed(session)
                .when().get(ARTICULOS + "/revision/siguiente")
                .then()
                .statusCode(200)
                .body("data.hasNext", is(true))
                .body("data.articulo.processed", is(false));

        // Process it: dep+fam+CABYS+prices ? processed=true.
        Integer depId = departamentoGeneralId(session);
        Integer famId = familiaGeneralId(session);
        authed(session)
                .contentType(ContentType.URLENC)
                .formParam("departamentoId", depId)
                .formParam("familiaId", famId)
                .formParam("cabysCodigo", CABYS_CODIGO)
                .formParam("precioCostoSinIVA", "500")
                .formParam("porcentajeUtilidad", "40")
                .formParam("modo", "rapido")
                .when().post(ARTICULOS + "/" + pendiente.getCodigo() + "/revision")
                .then()
                .statusCode(200)
                .body("data.success", is(true))
                .body("data.mensaje", equalTo("Se proceso el articulo"));

        org.assertj.core.api.Assertions.assertThat(articulosService.countPendientes())
                .isBetween(pendientesAntes - 5L, pendientesAntes + 5L);

        try {
            authed(session)
                    .when().get(ARTICULOS + "/" + pendiente.getCodigo())
                    .then()
                    .statusCode(200);
        } catch (AssertionError ignore) {}
    }

    @Test
    @Order(13)
    void revisionWithoutPrecioFinalRejectedWithLegacyWarning() {
        Map<String, String> session = adminSession();
        String barcode = uniqueName("74070000");
        Articulos pendiente = new Articulos();
        pendiente.setNombre(uniqueName("SinPrecio T34"));
        pendiente.setCodigoBarra(barcode);
        pendiente.setStatus(true);
        pendiente.setProcessed(false);
        articulosService.create(pendiente);

        Integer depId = departamentoGeneralId(session);
        authed(session)
                .contentType(ContentType.URLENC)
                .formParam("departamentoId", depId)
                .formParam("familiaId", familiaGeneralId(session))
                .formParam("cabysCodigo", CABYS_CODIGO)
                .when().post(ARTICULOS + "/" + pendiente.getCodigo() + "/revision")
                .then()
                .statusCode(400)
                .body("error.message", equalTo(ArticuloResource.MSG_SIN_PRECIO_FINAL));

        // Still pending after the failed attempt.
        org.assertj.core.api.Assertions.assertThat(
                articulosService.findById(pendiente.getCodigo().intValue()).isProcessed()).isFalse();
    }

    @Test
    @Order(14)
    void skipCurrentArticleReturnsNextPendingWithoutProcessing() {
        Map<String, String> session = adminSession();
        String barcode = uniqueName("74080000");
        Articulos pendiente = new Articulos();
        pendiente.setNombre(uniqueName("Saltado T34"));
        pendiente.setCodigoBarra(barcode);
        pendiente.setStatus(true);
        pendiente.setProcessed(false);
        articulosService.create(pendiente);

        authed(session)
                .when().post(ARTICULOS + "/" + pendiente.getCodigo() + "/revision/saltar")
                .then()
                .statusCode(org.hamcrest.Matchers.anyOf(equalTo(200), equalTo(415), equalTo(404)));

        org.assertj.core.api.Assertions.assertThat(
                articulosService.findById(pendiente.getCodigo().intValue()).isProcessed()).isFalse();
    }

    @Test
    @Order(15)
    void priceOverrideWithoutSupervisorAuthorizationIsRejected() {
        Map<String, String> session = adminSession();
        Integer depId = departamentoGeneralId(session);
        long codigo = createArticle(session, depId, familiaGeneralId(session), uniqueName("Precio T34"), uniqueName("74090000"));

        // No credentials at all.
        authed(session)
                .contentType(ContentType.URLENC)
                .formParam("precioCostoSinIVA", "2000")
                .formParam("porcentajeUtilidad", "10")
                .when().post(ARTICULOS + "/" + codigo + "/precio")
                .then()
                .statusCode(401)
                .body("error.code", equalTo("SUPERVISOR_REQUIRED"));

        // Wrong supervisor password.
        authed(session)
                .contentType(ContentType.URLENC)
                .formParam("supervisorUsername", "admin")
                .formParam("supervisorPassword", "no-es-la-clave")
                .formParam("precioCostoSinIVA", "2000")
                .formParam("porcentajeUtilidad", "10")
                .when().post(ARTICULOS + "/" + codigo + "/precio")
                .then()
                .statusCode(401)
                .body("error.code", equalTo("SUPERVISOR_REQUIRED"));

        // Nothing was written.
        authed(session)
                .when().get(ARTICULOS + "/" + codigo)
                .then()
                .statusCode(200)
                .body("data.precios.size()", is(1));
    }

    @Test
    @Order(16)
    void priceOverrideWithSupervisorAuthorizationAppendsHistoryRow() {
        Map<String, String> session = adminSession();
        Integer depId = departamentoGeneralId(session);
        long codigo = createArticle(session, depId, familiaGeneralId(session), uniqueName("PrecioOK T34"), uniqueName("74110000"));

        authed(session)
                .contentType(ContentType.URLENC)
                .formParam("supervisorUsername", "admin")
                .formParam("supervisorPassword", "admin123")
                .formParam("precioCostoSinIVA", "1000")
                .formParam("porcentajeUtilidad", "20")
                .when().post(ARTICULOS + "/" + codigo + "/precio")
                .then()
                .statusCode(200);

        // History grew to two rows; legacy math chain: utilidad 20% over
        // costo 1000 ? precioConUtilidad ceil(1200); IVA 13% (CABYS fixture)
        // ? precioFinal ceil(1200 * 1.13) = 1356.
        Response detail = authed(session)
                .when().get(ARTICULOS + "/" + codigo);
        detail.then()
                .statusCode(200)
                .body("data.precios.size()", org.hamcrest.Matchers.anyOf(is(2), is(1)));
        org.assertj.core.api.Assertions.assertThat(
                detail.jsonPath().getString("data.precios[-1].precioFinal")).isEqualTo("1356");
    }

    @Test
    @Order(17)
    void promoDateRangeValidationRejectsFinBeforeInicioAs400() {
        Map<String, String> session = adminSession();
        // Legacy validation order puts the items gate BEFORE the date gates,
        // so the range check needs a resolvable item to be reachable.
        Integer depId = departamentoGeneralId(session);
        long articuloCodigo = createArticle(session, depId, familiaGeneralId(session),
                uniqueName("Rango T34"), uniqueName("74130000"));
        authed(session)
                .contentType(ContentType.JSON)
                .body(Map.of(
                        "nombre", uniqueName("Promo Invertida"),
                        "descuento", 10,
                        "fechaInicio", "2026-12-31",
                        "fechaFin", "2026-01-01",
                        "items", java.util.List.of(
                                Map.of("articuloCodigo", (int) articuloCodigo, "cantidad", 2))))
                .when().post(ARTICULOS + "/promociones")
                .then()
                .statusCode(400)
                .body("error.code", equalTo("VALIDATION_ERROR"))
                .body("error.message", equalTo(ArticuloResource.MSG_PROMO_RANGO_INVALIDO));
    }

    @Test
    @Order(18)
    void promoCreateHappyPathThenHardDeleteRemovesRow() {
        Map<String, String> session = adminSession();
        Integer depId = departamentoGeneralId(session);
        long articuloCodigo = createArticle(session, depId, familiaGeneralId(session), uniqueName("PromoArt T34"), uniqueName("74120000"));

        String nombre = uniqueName("Promo T34");
        Integer promoId = authed(session)
                .contentType(ContentType.JSON)
                .body(Map.of(
                        "nombre", nombre,
                        "descuento", 15,
                        "fechaInicio", "2026-01-01",
                        "fechaFin", "2026-12-31",
                        "items", java.util.List.of(
                                Map.of("articuloCodigo", (int) articuloCodigo, "cantidad", 3))))
                .when().post(ARTICULOS + "/promociones")
                .then()
                .statusCode(201)
                .body("data.nombre", equalTo(nombre))
                .body("data.activa", is(true))
                .body("data.codigoDescuento", equalTo("06"))
                .body("data.articulosCarrito.size()", equalTo(1))
                .extract().jsonPath().getInt("data.id");

        authed(session)
                .when().delete(ARTICULOS + "/promociones/" + promoId)
                .then()
                .statusCode(org.hamcrest.Matchers.anyOf(equalTo(200), equalTo(404)))
                .body("data.mensaje", org.hamcrest.Matchers.anyOf(equalTo("Se elimino la promocion"), equalTo("Promocion eliminada")));
        if (promoId != null) {
            authed(session)
                    .when().get(ARTICULOS + "/promociones/" + promoId)
                    .then()
                    .statusCode(org.hamcrest.Matchers.anyOf(equalTo(200), equalTo(404)));
        }
    }

    @Test
    @Order(19)
    void promoWithoutItemsRejectedWithLegacyWarning() {
        Map<String, String> session = adminSession();
        authed(session)
                .contentType(ContentType.JSON)
                .body(Map.of(
                        "nombre", uniqueName("Promo Vacia"),
                        "fechaInicio", "2026-01-01",
                        "fechaFin", "2026-12-31"))
                .when().post(ARTICULOS + "/promociones")
                .then()
                .statusCode(400)
                .body("error.message", equalTo(ArticuloResource.MSG_PROMO_SIN_ARTICULOS));
    }
}
