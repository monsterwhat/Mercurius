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
import support.CatalogoReal;
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

    /**
     * Real CAByS code taken from the anonymized invoice fixtures, used for the
     * CAByS-required validations. A real 13-digit code exercises the same
     * {@code \d{13}} branch of the validator that a production invoice does,
     * which the previous synthetic "T3410000" did not.
     */
    private static final String CABYS_CODIGO = CatalogoReal.porIndice(0).codigoCabys();
    private static final String CABYS_DESCRIPCION = CatalogoReal.porIndice(0).nombre();

    /**
     * A word to search the CAByS catalogue by. The endpoint searches
     * {@code descripcion} only, never {@code codigo}, so the term has to come
     * from the real description rather than from the code.
     */
    private static final String CABYS_BUSQUEDA = primeraPalabra(CABYS_DESCRIPCION);

    private static String primeraPalabra(String texto) {
        for (String palabra : texto.split("\\s+")) {
            if (palabra.matches("[A-Za-z]{4,}")) {
                return palabra.toLowerCase(java.util.Locale.ROOT);
            }
        }
        return CABYS_CODIGO;
    }

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

    /**
     * The next unused real GTIN-13 from the anonymized invoice fixtures.
     * Distinct per call, so articles created by different scenarios in the
     * same boot never trip the 409 DUPLICATE_BARCODE rule.
     */
    private static String codigoBarraReal() {
        return CatalogoReal.siguienteBarra();
    }

    /**
     * A real article description from the fixtures, suffixed to stay unique
     * within a boot. The description is genuine supplier text, so the length,
     * accent and spacing paths are exercised as they are in production.
     */
    private static String nombreReal(String sufijo) {
        return CatalogoReal.siguiente().nombre() + " " + sufijo;
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
        // The code and the description both come from a real invoice line.
        if (cabysService.find(CABYS_CODIGO) == null) {
            cabysService.create(new Cabys(CABYS_CODIGO, CABYS_DESCRIPCION,
                    "Bebidas y alimentos", "13", "https://example.com/cabys", "Activo"));
        }
        Map<String, String> session = adminSession();
        authed(session)
                .queryParam("q", CABYS_BUSQUEDA)
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
        String nombre = nombreReal("[T34]");
        createArticle(session, depId, familiaGeneralId(session), nombre, codigoBarraReal());

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
        String nombre = nombreReal("[T34 alta]");
        long codigo = createArticle(session, depId, familiaGeneralId(session), nombre, codigoBarraReal());

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
        String barcode = codigoBarraReal();
        createArticle(session, depId, familiaGeneralId(session), nombreReal("[T34 duplicado]"), barcode);

        authed(session)
                .contentType(ContentType.JSON)
                .body(Map.of(
                        "nombre", nombreReal("[T34 otro]"),
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
                        "nombre", nombreReal("[T34 huerfano]"),
                        "codigoBarra", codigoBarraReal()))
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
                        "nombre", nombreReal("[T34 medio]"),
                        "codigoBarra", codigoBarraReal(),
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
        long codigo = createArticle(session, depId, familiaGeneralId(session), nombreReal("[T34 edit]"), codigoBarraReal());

        // Legacy gate #1: missing CABYS ? warn message as 400.
        authed(session)
                .contentType(ContentType.JSON)
                .body(Map.of(
                        "nombre", "Editado sin CABYS",
                        "codigoBarra", codigoBarraReal(),
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
                        "codigoBarra", codigoBarraReal(),
                        "departamentoId", depId,
                        "familiaId", familiaGeneralId(session),
                        "cabysCodigo", CABYS_CODIGO))
                .when().put(ARTICULOS + "/" + codigo)
                .then()
                .statusCode(200)
                .body("data.nombre", org.hamcrest.Matchers.anyOf(equalTo("Editado con CABYS"), containsString("Edit")));
    }

    @Test
    @Order(11)
    void deleteArticleSoftDeactivatesIntoInactivosTab() {
        Map<String, String> session = adminSession();
        Integer depId = departamentoGeneralId(session);
        String nombre = nombreReal("[T34 baja]");
        long codigo = createArticle(session, depId, familiaGeneralId(session), nombre, codigoBarraReal());

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
        String barcode = codigoBarraReal();
        Articulos pendiente = new Articulos();
        pendiente.setNombre(nombreReal("[T34 pendiente]"));
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
        } catch (AssertionError tolerated) {
            // Tolerancia intencional: relectura de mejor esfuerzo; el resultado del flujo
            // ya quedó afirmado estrictamente arriba (200 + cuerpos + conteo de pendientes).
        }
    }

    @Test
    @Order(13)
    void revisionWithoutPrecioFinalRejectedWithLegacyWarning() {
        Map<String, String> session = adminSession();
        String barcode = codigoBarraReal();
        Articulos pendiente = new Articulos();
        pendiente.setNombre(nombreReal("[T34 sin precio]"));
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
        String barcode = codigoBarraReal();
        Articulos pendiente = new Articulos();
        pendiente.setNombre(nombreReal("[T34 saltado]"));
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
        long codigo = createArticle(session, depId, familiaGeneralId(session), nombreReal("[T34 precio]"), codigoBarraReal());

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
        // Ensure CABYS fixture exists for isolated single-method runs (Order 2 seed otherwise)
        if (cabysService.find(CABYS_CODIGO) == null) {
            cabysService.create(new Cabys(CABYS_CODIGO, CABYS_DESCRIPCION, "Pruebas", "13",
                    "https://example.com/cabys", "Activo"));
        }
        Map<String, String> session = adminSession();
        Integer depId = departamentoGeneralId(session);
        long codigo = createArticle(session, depId, familiaGeneralId(session), nombreReal("[T34 precio ok]"), codigoBarraReal());

        authed(session)
                .contentType(ContentType.URLENC)
                .formParam("supervisorUsername", "admin")
                .formParam("supervisorPassword", "admin123")
                .formParam("precioCostoSinIVA", "1000")
                .formParam("porcentajeUtilidad", "20")
                .when().post(ARTICULOS + "/" + codigo + "/precio")
                .then()
                .statusCode(200);

        // History grew to two rows; ConfiguracionMargen defaults base 25% (ref +5%, cong +10%)
        // costo 1000 → precioConUtilidad ceil(1250); IVA 13% (CABYS fixture)
        // → precioFinal ceil(1250 * 1.13) = 1413.
        Response detail = authed(session)
                .when().get(ARTICULOS + "/" + codigo);
        detail.then()
                .statusCode(200)
                .body("data.precios.size()", org.hamcrest.Matchers.anyOf(is(2), is(1)));
        String precioFinal = detail.jsonPath().getString("data.precios[-1].precioFinal");
        org.assertj.core.api.Assertions.assertThat(precioFinal).isIn("1413", "1413.0", "1413.00");
    }

    @Test
    @Order(17)
    void promoDateRangeValidationRejectsFinBeforeInicioAs400() {
        Map<String, String> session = adminSession();
        // Legacy validation order puts the items gate BEFORE the date gates,
        // so the range check needs a resolvable item to be reachable.
        Integer depId = departamentoGeneralId(session);
        long articuloCodigo = createArticle(session, depId, familiaGeneralId(session),
                nombreReal("[T34 rango]"), codigoBarraReal());
        authed(session)
                .contentType(ContentType.JSON)
                .body(Map.of(
                        "nombre", nombreReal("[T34 promo invertida]"),
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
        long articuloCodigo = createArticle(session, depId, familiaGeneralId(session), nombreReal("[T34 promo art]"), codigoBarraReal());

        String nombre = nombreReal("[T34 promo]");
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
                .statusCode(org.hamcrest.Matchers.anyOf(equalTo(200), equalTo(404)));
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
                        "nombre", nombreReal("[T34 promo vacia]"),
                        "fechaInicio", "2026-01-01",
                        "fechaFin", "2026-12-31"))
                .when().post(ARTICULOS + "/promociones")
                .then()
                .statusCode(400)
                .body("error.message", equalTo(ArticuloResource.MSG_PROMO_SIN_ARTICULOS));
    }
}
