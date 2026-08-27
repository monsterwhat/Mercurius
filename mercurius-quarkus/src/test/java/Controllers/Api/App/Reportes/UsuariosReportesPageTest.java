package Controllers.Api.App.Reportes;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import Models.Users;
import Services.LoginService;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * T20 — page contract for {@code GET /app/reportes/usuarios}: admin-only gate
 * (legacy web.xml /secured/pages/Usuarios/*), stat cards, HTMX fragment and
 * row-count parity against {@link LoginService#listAll()}. Password hashes
 * must never appear in the markup.
 */
@QuarkusTest
@Tag("reportes-pages")
class UsuariosReportesPageTest extends support.ContextPathIsolation {

    private static final String PAGE_URL = "/Mercurius/app/reportes/usuarios";

    @Inject
    LoginService loginService;

    @Inject
    jakarta.transaction.UserTransaction utx;

    @Test
    @TestSecurity(user = "admin", roles = {"admin"})
    void adminSeesStatCardsAndTable() {
        given()
                .when().get(PAGE_URL)
                .then()
                .statusCode(200)
                .body(containsString("Gestión de Usuarios"))
                .body(containsString("Activos"))
                .body(containsString("Inactivos"))
                .body(containsString("data-kit-table"));
    }

    @Test
    @TestSecurity(user = "gestor", roles = {"usuario"})
    void usuarioRoleIsForbidden() {
        given()
                .when().get(PAGE_URL)
                .then()
                .statusCode(403);
    }

    @Test
    @TestSecurity(user = "admin", roles = {"admin"})
    void hxRequestReturnsFragmentWithoutLayout() {
        given()
                .header("HX-Request", "true")
                .when().get(PAGE_URL)
                .then()
                .statusCode(200)
                .body(containsString("data-kit-table"))
                .body(not(containsString("<footer")));
    }

    @Test
    @TestSecurity(user = "admin", roles = {"admin"})
    void rowCountMatchesDirectServiceCallAndNeverLeaksHashes() throws Exception {
        Users seeded = null;
        String username = "IT-T20-User-" + UUID.randomUUID().toString().substring(0, 8);
        try {
            // LoginService.create() overrides GService.create without inheriting
            // its @Transactional binding, so the persist needs an active
            // transaction (UsersResource supplies one in production).
            seeded = new Users();
            seeded.setUsername(username);
            seeded.setPassword("IT-T20-plain-password");
            seeded.setGroupName("registro");
            seeded.setStatus(true);
            utx.begin();
            try {
                loginService.create(seeded);
            } finally {
                utx.commit();
            }

            String html = given()
                    .queryParam("size", 500)
                    .when().get(PAGE_URL)
                    .then()
                    .statusCode(200)
                    .extract().asString();

            assertEquals(loginService.listAll().size(), FacturasReportesPageTest.countRows(html),
                    "usuarios page rows must equal LoginService.listAll().size()");
            org.junit.jupiter.api.Assertions.assertTrue(html.contains(username),
                    "seeded username must be visible on the listing");
            assertFalse(html.contains(seeded.getPassword()),
                    "the stored (hashed or raw) password value must never be rendered");
        } finally {
            if (seeded != null && seeded.getId() != null) {
                utx.begin();
                try {
                    loginService.delete(seeded);
                } finally {
                    utx.commit();
                }
            }
        }
    }
}
