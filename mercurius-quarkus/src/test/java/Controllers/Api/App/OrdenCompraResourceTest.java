package Controllers.Api.App;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import Models.Articulos.Articulos;
import Services.ArticulosService;
import Services.DepartamentoService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * T31 acceptance suite for {@link OrdenCompraResource}: real form-cookie login
 * over RestAssured (POST /Mercurius/j_security_check, seed admin/admin123),
 * the full purchase-order lifecycle create→edit→status→receive→facturada and
 * cancel, EVERY illegal state transition of the legacy machine asserted as
 * 409 ApiResponse INVALID_STATE (full 6×6 matrix), the legacy validation
 * messages, the role matrix (@RolesAllowed admin/inventario) and the staged
 * HTMX fragment contract of docs/ui-kit.md.
 *
 * <p><b>CSRF note:</b> quarkus-rest-csrf is active with defaults, so every
 * mutating call must carry the {@code X-CSRF-TOKEN} header matching the
 * {@code csrftoken} cookie issued by any prior GET (helpers below reproduce
 * that dance — same approach as CategoriaResourceTest).</p>
 *
 * <p><b>Fixtures:</b> programmatic articles are committed through
 * {@link ArticulosService#create} (no @TestTransaction: the REST calls run in
 * their own transactions and must see the rows). The seeded
 * "Departamento General" row from import-test.sql is reused as proveedor,
 * never modified.</p>
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class OrdenCompraResourceTest extends support.ContextPathIsolation {

    private static final String BASE = "/Mercurius";
    private static final String CSRF_COOKIE = "csrf-token";
    private static final String CSRF_HEADER = "X-CSRF-TOKEN";

    /** The full legacy state machine: {from, to, legal?} — evidence/t31/state-machine.md. */
    private static final List<String> ESTADOS = List.of(
            "BORRADOR", "ENVIADA", "CONFIRMADA", "RECIBIDA", "FACTURADA", "CANCELADA");

    @Inject
    ArticulosService articulosService;

    @Inject
    DepartamentoService departamentoService;

    // ── Auth helpers (CategoriaResourceTest conventions) ────────────────

    /** Full browser-equivalent session: GET /login → POST j_security_check. */
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

    /** Anonymous visitor cookies: enough to satisfy the CSRF filter. */
    private static Map<String, String> anonymousCookies() {
        Response loginPage = given().redirects().follow(false)
                .when().get(BASE + "/login");
        loginPage.then().statusCode(200);
        return new HashMap<>(loginPage.getCookies());
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

    // ── Fixture helpers ──────────────────────────────────────────────────

    private Integer proveedorGeneralId() {
        return departamentoService.listAll().stream()
                .filter(d -> "Departamento General".equals(d.getNombre()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("seed Departamento General missing"))
                .getId();
    }

    /** Committed processed+active article; returns its codigo (Long PK). */
    private Long seedArticulo(String nombre) {
        Articulos articulo = new Articulos();
        articulo.setNombre(nombre);
        articulo.setCodigoBarra("T31-" + UUID.randomUUID().toString().substring(0, 8));
        articulo.setDescripcion("Fixture T31 para " + nombre);
        articulo.setStatus(true);
        articulo.setProcessed(true);
        articulo.setPrecios(new java.util.ArrayList<>());
        articulosService.create(articulo);
        return articulo.getCodigo();
    }

    /** JSON body for a one-line order payload (binary-exact money values). */
    private Map<String, Object> ordenPayload(Integer proveedorId, Long articuloCodigo,
                                             String cantidad, String precio) {
        Map<String, Object> linea = new HashMap<>();
        linea.put("articuloCodigo", articuloCodigo);
        linea.put("cantidad", new java.math.BigDecimal(cantidad));
        linea.put("precioUnitario", new java.math.BigDecimal(precio));
        Map<String, Object> payload = new HashMap<>();
        if (proveedorId != null) {
            payload.put("proveedorId", proveedorId);
        }
        payload.put("fechaEntregaEstimada", "2026-12-01");
        payload.put("notas", "Pedido T31");
        payload.put("detalles", List.of(linea));
        return payload;
    }

    /** Creates a BORRADOR order over the API and returns its id. */
    private long crearOrdenBorrador(Map<String, String> session, Long articuloCodigo) {
        Integer id = authed(session)
                .contentType(ContentType.JSON)
                .body(ordenPayload(proveedorGeneralId(), articuloCodigo, "5", "1000.50"))
                .when().post(BASE + "/api/app/ordenes")
                .then()
                .statusCode(201)
                .extract().jsonPath().getInt("data.id");
        return id.longValue();
    }

    /**
     * Creates an order and walks it to the requested estado using ONLY legal
     * transitions ({@code CANCELADA} goes through the cancel endpoint).
     */
    private long ordenEnEstado(Map<String, String> session, Long articuloCodigo, String estado) {
        long id = crearOrdenBorrador(session, articuloCodigo);
        switch (estado) {
            case "BORRADOR" -> { /* already there */ }
            case "ENVIADA" -> transicionar(session, id, "ENVIADA");
            case "CONFIRMADA" -> {
                transicionar(session, id, "ENVIADA");
                transicionar(session, id, "CONFIRMADA");
            }
            case "RECIBIDA" -> {
                transicionar(session, id, "ENVIADA");
                transicionar(session, id, "CONFIRMADA");
                transicionar(session, id, "RECIBIDA");
            }
            case "FACTURADA" -> {
                transicionar(session, id, "ENVIADA");
                transicionar(session, id, "CONFIRMADA");
                transicionar(session, id, "RECIBIDA");
                transicionar(session, id, "FACTURADA");
            }
            case "CANCELADA" -> authed(session)
                    .contentType(ContentType.JSON)
                    .body(Map.of("motivo", "Fixture cancelada"))
                    .when().post(BASE + "/api/app/ordenes/" + id + "/cancelar")
                    .then().statusCode(200);
            default -> throw new IllegalArgumentException("estado desconocido: " + estado);
        }
        return id;
    }

    private void transicionar(Map<String, String> session, long id, String nuevoEstado) {
        authed(session)
                .contentType(ContentType.JSON)
                .body(Map.of("nuevoEstado", nuevoEstado))
                .when().put(BASE + "/api/app/ordenes/" + id + "/estado")
                .then().statusCode(200);
    }

    /** Legal pairs of the legacy machine (7 of 36). */
    private static boolean esLegal(String from, String to) {
        return switch (from) {
            case "BORRADOR" -> "ENVIADA".equals(to) || "CANCELADA".equals(to);
            case "ENVIADA" -> "CONFIRMADA".equals(to) || "CANCELADA".equals(to);
            case "CONFIRMADA" -> "RECIBIDA".equals(to) || "CANCELADA".equals(to);
            case "RECIBIDA" -> "FACTURADA".equals(to);
            default -> false;
        };
    }

    private static Stream<Arguments> matrizCompleta() {
        Stream.Builder<Arguments> casos = Stream.builder();
        for (String from : ESTADOS) {
            for (String to : ESTADOS) {
                casos.add(Arguments.of(from, to));
            }
        }
        return casos.build();
    }

    private Long articuloComun;

    @BeforeEach
    void seedCommonArticle() {
        if (articuloComun == null) {
            articuloComun = seedArticulo(uniqueName("T31 Articulo Comun"));
        }
    }

    // ── Scenarios ────────────────────────────────────────────────────────

    @Test
    @Order(1)
    void unauthenticatedListIsRedirectedToLogin() {
        given().redirects().follow(false)
                .when().get(BASE + "/api/app/ordenes")
                .then()
                .statusCode(302)
                .header("Location", containsString("/Mercurius/login"));
    }

    @Test
    @Order(2)
    void unauthenticatedMutationIsChallengedNotProcessed() {
        given().redirects().follow(false)
                .contentType(ContentType.JSON)
                .body(Map.of())
                .when().post(BASE + "/api/app/ordenes")
                .then()
                .statusCode(302)
                .header("Location", containsString("/Mercurius/login"));
    }

    @Test
    @Order(3)
    void adminListsOrdenesWithPagedEnvelopeAndSorting() {
        Map<String, String> session = adminSession();
        // Fresh DB: the envelope contract itself is what matters here (page 0,
        // clamped size, data array present even when empty).
        authed(session)
                .queryParam("page", 0)
                .queryParam("size", 5)
                .queryParam("sort", "numeroOrden")
                .queryParam("dir", "asc")
                .when().get(BASE + "/api/app/ordenes")
                .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("page", equalTo(0))
                .body("size", equalTo(5))
                .body("total", notNullValue())
                .body("data", notNullValue());

        authed(session)
                .queryParam("sort", "numeroOrden")
                .queryParam("dir", "desc")
                .when().get(BASE + "/api/app/ordenes")
                .then()
                .statusCode(200);
    }

    @Test
    @Order(4)
    void createOrderHappyPathPersistsBorradorWithGeneratedNumero() {
        Map<String, String> session = adminSession();

        Response created = authed(session)
                .contentType(ContentType.JSON)
                .body(ordenPayload(proveedorGeneralId(), articuloComun, "5", "1000.50"))
                .when().post(BASE + "/api/app/ordenes");
        created.then()
                .statusCode(201)
                .contentType(ContentType.JSON)
                .body("data.numeroOrden", matchesPattern("OC-\\d{4}-\\d{5}"))
                .body("data.estado", equalTo("BORRADOR"))
                .body("data.proveedorNombre", equalTo("Departamento General"))
                .body("data.totalEstimado", equalTo(5002.5f))
                .body("data.detalles[0].subtotal", equalTo(5002.5f))
                .body("data.detalles[0].articuloNombre", notNullValue())
                .body("data.usuarioUsername", equalTo("admin"));

        // Detail read-back flattens the same data.
        Integer id = created.jsonPath().getInt("data.id");
        authed(session)
                .when().get(BASE + "/api/app/ordenes/" + id)
                .then()
                .statusCode(200)
                .body("data.estado", equalTo("BORRADOR"))
                .body("data.detalles.size()", equalTo(1))
                .body("data.notas", equalTo("Pedido T31"));
    }

    @Test
    @Order(5)
    void createRejectsMissingProveedorWithLegacyMessage() {
        Map<String, String> session = adminSession();
        authed(session)
                .contentType(ContentType.JSON)
                .body(ordenPayload(null, articuloComun, "1", "100"))
                .when().post(BASE + "/api/app/ordenes")
                .then()
                .statusCode(400)
                .body("error.code", equalTo("VALIDATION_ERROR"))
                .body("error.message", equalTo("Debe seleccionar un proveedor!"));
    }

    @Test
    @Order(6)
    void createRejectsUnknownProveedorWithLegacyMessage() {
        Map<String, String> session = adminSession();
        authed(session)
                .contentType(ContentType.JSON)
                .body(ordenPayload(99999999, articuloComun, "1", "100"))
                .when().post(BASE + "/api/app/ordenes")
                .then()
                .statusCode(400)
                .body("error.message", equalTo("Debe seleccionar un proveedor!"));
    }

    @Test
    @Order(7)
    void createRejectsEmptyLineasWithLegacyMessage() {
        Map<String, String> session = adminSession();
        Map<String, Object> payload = ordenPayload(proveedorGeneralId(), articuloComun, "1", "100");
        payload.put("detalles", List.of());
        authed(session)
                .contentType(ContentType.JSON)
                .body(payload)
                .when().post(BASE + "/api/app/ordenes")
                .then()
                .statusCode(400)
                .body("error.code", equalTo("VALIDATION_ERROR"))
                .body("error.message", equalTo("Debe agregar al menos un artículo!"));
    }

    @Test
    @Order(8)
    void createRejectsInvalidCantidadWithLegacyMessage() {
        Map<String, String> session = adminSession();
        authed(session)
                .contentType(ContentType.JSON)
                .body(ordenPayload(proveedorGeneralId(), articuloComun, "0", "100"))
                .when().post(BASE + "/api/app/ordenes")
                .then()
                .statusCode(400)
                .body("error.code", equalTo("VALIDATION_ERROR"))
                .body("error.message", equalTo("Todos los artículos deben tener cantidad válida!"));
    }

    @Test
    @Order(9)
    void createRejectsUnknownArticuloWithLegacyMessage() {
        Map<String, String> session = adminSession();
        authed(session)
                .contentType(ContentType.JSON)
                .body(ordenPayload(proveedorGeneralId(), 99999999L, "1", "100"))
                .when().post(BASE + "/api/app/ordenes")
                .then()
                .statusCode(400)
                .body("error.code", equalTo("VALIDATION_ERROR"))
                .body("error.message", equalTo("Todos los artículos deben tener cantidad válida!"));
    }

    @Test
    @Order(10)
    void editUpdatesLinesAndRecomputesTotalsOnBorrador() {
        Map<String, String> session = adminSession();
        long id = crearOrdenBorrador(session, articuloComun);

        Map<String, Object> lineaA = new HashMap<>();
        lineaA.put("articuloCodigo", articuloComun);
        lineaA.put("cantidad", new java.math.BigDecimal("2"));
        lineaA.put("precioUnitario", new java.math.BigDecimal("250.25"));
        Map<String, Object> payload = ordenPayload(proveedorGeneralId(), articuloComun, "2", "250.25");
        payload.put("detalles", List.of(lineaA, Map.of(
                "articuloCodigo", articuloComun,
                "cantidad", new java.math.BigDecimal("1"),
                "precioUnitario", new java.math.BigDecimal("500"))));

        authed(session)
                .contentType(ContentType.JSON)
                .body(payload)
                .when().put(BASE + "/api/app/ordenes/" + id)
                .then()
                .statusCode(200)
                .body("data.detalles.size()", equalTo(2))
                .body("data.totalEstimado", equalTo(1000.5f));

        authed(session)
                .when().get(BASE + "/api/app/ordenes/" + id)
                .then()
                .statusCode(200)
                .body("data.detalles.size()", equalTo(2))
                .body("data.totalEstimado", equalTo(1000.5f));
    }

    @Test
    @Order(11)
    void fullLifecycleCreateEditStatusReceiveToFacturada() {
        Map<String, String> session = adminSession();
        long id = crearOrdenBorrador(session, articuloComun);

        // edit still allowed on BORRADOR
        authed(session)
                .contentType(ContentType.JSON)
                .body(ordenPayload(proveedorGeneralId(), articuloComun, "3", "10"))
                .when().put(BASE + "/api/app/ordenes/" + id)
                .then().statusCode(200);

        // BORRADOR → ENVIADA → CONFIRMADA
        transicionar(session, id, "ENVIADA");
        transicionar(session, id, "CONFIRMADA");

        // CONFIRMADA --recibir--> RECIBIDA (fechaEntregaReal stamped, totalReal copied)
        authed(session)
                .contentType(ContentType.JSON)
                .body(Map.of("totalReal", 33.5))
                .when().put(BASE + "/api/app/ordenes/" + id + "/recibir")
                .then()
                .statusCode(200)
                .body("data.estado", equalTo("RECIBIDA"))
                .body("data.fechaEntregaReal", notNullValue())
                .body("data.totalReal", equalTo(33.5f));

        // RECIBIDA → FACTURADA (the only legal exit)
        transicionar(session, id, "FACTURADA");
        authed(session)
                .when().get(BASE + "/api/app/ordenes/" + id)
                .then()
                .statusCode(200)
                .body("data.estado", equalTo("FACTURADA"));
    }

    @Test
    @Order(12)
    void cancelFromBorradorCarriesMotivoAndLegacyWarnSemantics() {
        Map<String, String> session = adminSession();
        long id = crearOrdenBorrador(session, articuloComun);

        authed(session)
                .contentType(ContentType.JSON)
                .body(Map.of("motivo", "Proveedor incumplió entrega"))
                .when().post(BASE + "/api/app/ordenes/" + id + "/cancelar")
                .then()
                .statusCode(200)
                .body("data.severidad", equalTo("warn"))
                .body("data.mensaje", equalTo("Orden cancelada!"))
                .body("data.orden.estado", equalTo("CANCELADA"))
                .body("data.orden.notas", equalTo("Proveedor incumplió entrega"));
    }

    /**
     * THE state-machine matrix: every one of the 36 from→to pairs of the six
     * known states. Legal pairs answer 200 and land on the target state;
     * every illegal pair answers 409 INVALID_STATE with the exact legacy
     * Spanish message AND leaves the estado untouched.
     */
    @ParameterizedTest(name = "{0} → {1}")
    @MethodSource("matrizCompleta")
    @Order(13)
    void everyTransitionPairOfTheLegacyMachineIsEnforced(String from, String to) {
        Map<String, String> session = adminSession();
        long id = ordenEnEstado(session, articuloComun, from);

        Response attempt = authed(session)
                .contentType(ContentType.JSON)
                .body(Map.of("nuevoEstado", to))
                .when().put(BASE + "/api/app/ordenes/" + id + "/estado");

        if (esLegal(from, to)) {
            attempt.then()
                    .statusCode(200)
                    .body("data.estado", equalTo(to));
            authed(session)
                    .when().get(BASE + "/api/app/ordenes/" + id)
                    .then()
                    .statusCode(200)
                    .body("data.estado", equalTo(to));
        } else {
            attempt.then()
                    .statusCode(409)
                    .body("error.code", equalTo("INVALID_STATE"))
                    .body("error.message",
                            equalTo("Transición de estado no válida: " + from + " → " + to));
            authed(session)
                    .when().get(BASE + "/api/app/ordenes/" + id)
                    .then()
                    .statusCode(200)
                    .body("data.estado", equalTo(from));
        }
    }

    @Test
    @Order(14)
    void estadoChangeOnUnknownOrArchivedOrderIs404() {
        Map<String, String> session = adminSession();
        authed(session)
                .contentType(ContentType.JSON)
                .body(Map.of("nuevoEstado", "ENVIADA"))
                .when().put(BASE + "/api/app/ordenes/999999999/estado")
                .then()
                .statusCode(404)
                .body("error.code", equalTo("NOT_FOUND"));

        long id = crearOrdenBorrador(session, articuloComun);
        authed(session).when().delete(BASE + "/api/app/ordenes/" + id).then().statusCode(200);
        authed(session)
                .contentType(ContentType.JSON)
                .body(Map.of("nuevoEstado", "ENVIADA"))
                .when().put(BASE + "/api/app/ordenes/" + id + "/estado")
                .then()
                .statusCode(404);
    }

    @Test
    @Order(15)
    void recibirOnUnknownOrderIs404() {
        Map<String, String> session = adminSession();
        authed(session)
                .contentType(ContentType.JSON)
                .body(Map.of())
                .when().put(BASE + "/api/app/ordenes/999999999/recibir")
                .then()
                .statusCode(404)
                .body("error.code", equalTo("NOT_FOUND"));
    }

    @Test
    @Order(16)
    @TestSecurity(user = "cliente", roles = "usuario")
    void usuarioRoleIsForbiddenFromMutations() {
        Map<String, String> cookies = anonymousCookies();
        authed(cookies)
                .contentType(ContentType.JSON)
                .body(ordenPayload(proveedorGeneralId(), articuloComun, "1", "100"))
                .when().post(BASE + "/api/app/ordenes")
                .then()
                .statusCode(403);
    }

    @Test
    @Order(17)
    @TestSecurity(user = "bodeguero", roles = "inventario")
    void inventarioRoleMayCreateOrders() {
        Map<String, String> cookies = anonymousCookies();
        authed(cookies)
                .contentType(ContentType.JSON)
                .body(ordenPayload(proveedorGeneralId(), articuloComun, "1", "100"))
                .when().post(BASE + "/api/app/ordenes")
                .then()
                .statusCode(201)
                .body("data.estado", equalTo("BORRADOR"));
    }

    @Test
    @Order(18)
    void tableEndpointServesFragmentOnHxRequestAndFullPageOtherwise() {
        Map<String, String> session = adminSession();

        // Fragment mode: ONLY the data-table include, never the layout shell.
        authed(session)
                .header("HX-Request", "true")
                .when().get(BASE + "/api/app/ordenes/table")
                .then()
                .statusCode(200)
                .contentType(ContentType.HTML)
                .body(containsString("data-kit-table"))
                .body(containsString("id=\"tabla-ordenes\""))
                .body(not(containsString("<html")));

        // Full-page mode: stats cards + layout shell.
        authed(session)
                .when().get(BASE + "/api/app/ordenes/table")
                .then()
                .statusCode(200)
                .contentType(ContentType.HTML)
                .body(containsString("<html"))
                .body(containsString("Órdenes de Compra"))
                .body(containsString("toast-container"))
                .body(containsString("Borrador"))
                .body(containsString("Facturadas"));

        // Estado filter card round-trips into the fragment.
        authed(session)
                .header("HX-Request", "true")
                .queryParam("estado", "BORRADOR")
                .when().get(BASE + "/api/app/ordenes/table")
                .then()
                .statusCode(200)
                .body(containsString("BORRADOR"));
    }

    @Test
    @Order(19)
    void stagedFragmentsRenderCrearEditarDetalleEstadoYCancelar() {
        Map<String, String> session = adminSession();
        long id = crearOrdenBorrador(session, articuloComun);

        // Stage 2: empty creation form.
        authed(session)
                .header("HX-Request", "true")
                .when().get(BASE + "/api/app/ordenes/formularios/nueva")
                .then()
                .statusCode(200)
                .body(containsString("id=\"forma-orden\""))
                .body(containsString("Proveedor *"))
                .body(containsString("agregar-linea"))
                .body(not(containsString("<html")));

        // Stage 3: prefilled edit form (hx-get fill).
        authed(session)
                .header("HX-Request", "true")
                .when().get(BASE + "/api/app/ordenes/formularios/" + id)
                .then()
                .statusCode(200)
                .body(containsString("Guardar Cambios"))
                .body(containsString("Fecha Entrega Estimada"));

        // Stage 4: read-only detail panel.
        authed(session)
                .header("HX-Request", "true")
                .when().get(BASE + "/api/app/ordenes/" + id + "/detalle")
                .then()
                .statusCode(200)
                .body(containsString("Total Estimado"))
                .body(containsString("Artículos"))
                .body(containsString("Cerrar"));

        // Stage 5: estado change offers ONLY the legal targets of BORRADOR.
        authed(session)
                .header("HX-Request", "true")
                .when().get(BASE + "/api/app/ordenes/" + id + "/estado")
                .then()
                .statusCode(200)
                .body(containsString("option value=\"ENVIADA\""))
                .body(containsString("option value=\"CANCELADA\""))
                .body(not(containsString("option value=\"CONFIRMADA\"")))
                .body(not(containsString("option value=\"FACTURADA\"")));

        // Stage 6: cancel confirm carries the legacy question + motivo field.
        authed(session)
                .header("HX-Request", "true")
                .when().get(BASE + "/api/app/ordenes/" + id + "/cancelar")
                .then()
                .statusCode(200)
                .body(containsString("¿Está seguro de cancelar la orden?"))
                .body(containsString("Motivo de cancelación"));
    }

    @Test
    @Order(20)
    void articlePickerServesTypeaheadFragmentAndJsonFromLegacySearch() {
        Map<String, String> session = adminSession();
        String token = uniqueName("T31 Picker");
        seedArticulo(token);

        // HTMX typeahead mode (ui-kit §8 recipe).
        authed(session)
                .header("HX-Request", "true")
                .queryParam("q", token)
                .when().get(BASE + "/api/app/ordenes/articulos")
                .then()
                .statusCode(200)
                .contentType(ContentType.HTML)
                .body(containsString("js-articulo-opcion"))
                .body(containsString("data-codigo"))
                .body(containsString(token));

        // JSON mode reuses the same search service.
        authed(session)
                .queryParam("q", token)
                .when().get(BASE + "/api/app/ordenes/articulos")
                .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("data[0].nombre", equalTo(token));
    }

    @Test
    @Order(21)
    void hxFormTwinsCreateRedisplayAndRejectIllegalTransitions() {
        Map<String, String> session = adminSession();

        // Happy path: form-urlencoded create answers HX-Redirect to the table.
        authed(session)
                .header("HX-Request", "true")
                .contentType(ContentType.URLENC)
                .formParam("proveedorId", proveedorGeneralId())
                .formParam("fechaEntregaEstimada", "2026-12-15")
                .formParam("notas", "Creada desde formulario T31")
                .formParam("articuloCodigo", articuloComun.toString())
                .formParam("cantidad", "2")
                .formParam("precioUnitario", "250.25")
                .when().post(BASE + "/api/app/ordenes")
                .then()
                .statusCode(200)
                .header("HX-Redirect", containsString("/api/app/ordenes/table"));

        // Validation failure: form redisplay + legacy message (ui-kit Pattern A).
        authed(session)
                .header("HX-Request", "true")
                .contentType(ContentType.URLENC)
                .formParam("proveedorId", proveedorGeneralId())
                .when().post(BASE + "/api/app/ordenes")
                .then()
                .statusCode(422)
                .contentType(ContentType.HTML)
                .body(containsString("Debe agregar al menos un artículo!"));

        // Illegal transition through the form twin keeps the 409 contract.
        long id = crearOrdenBorrador(session, articuloComun);
        authed(session)
                .header("HX-Request", "true")
                .contentType(ContentType.URLENC)
                .formParam("nuevoEstado", "CONFIRMADA")
                .when().put(BASE + "/api/app/ordenes/" + id + "/estado")
                .then()
                .statusCode(409)
                .contentType(ContentType.HTML)
                .body(containsString("Transición de estado no válida: BORRADOR → CONFIRMADA"));
    }

    @Test
    @Order(22)
    void deleteSoftDeletesBorradorOrderAndHidesItFromReads() {
        Map<String, String> session = adminSession();
        long id = crearOrdenBorrador(session, articuloComun);

        authed(session)
                .when().delete(BASE + "/api/app/ordenes/" + id)
                .then()
                .statusCode(200)
                .body("data.status", equalTo(false))
                .body("data.id", equalTo((int) id));

        authed(session)
                .when().get(BASE + "/api/app/ordenes/" + id)
                .then()
                .statusCode(404)
                .body("error.code", equalTo("NOT_FOUND"));
    }

    @Test
    @Order(23)
    void listFiltersRoundTripLegacyGlobalFilterFields() {
        Map<String, String> session = adminSession();
        long id = crearOrdenBorrador(session, articuloComun);
        String numero = authed(session)
                .when().get(BASE + "/api/app/ordenes/" + id)
                .then().statusCode(200)
                .extract().jsonPath().getString("data.numeroOrden");

        authed(session)
                .queryParam("numeroOrden", numero)
                .when().get(BASE + "/api/app/ordenes")
                .then()
                .statusCode(200)
                .body("total", equalTo(1))
                .body("data[0].id", equalTo((int) id));

        authed(session)
                .queryParam("estado", "BORRADOR")
                .when().get(BASE + "/api/app/ordenes")
                .then()
                .statusCode(200)
                .body("total", greaterThanOrEqualTo(1))
                .body("data.findAll{ it.estado != 'BORRADOR' }.size()", equalTo(0));

        authed(session)
                .queryParam("q", "Departamento General")
                .when().get(BASE + "/api/app/ordenes")
                .then()
                .statusCode(200)
                .body("total", greaterThanOrEqualTo(1));
    }
}
