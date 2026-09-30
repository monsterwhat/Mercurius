package Controllers.filters;

import static org.assertj.core.api.Assertions.assertThat;

import Controllers.filters.PublicApiJwtFilter.Decision;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Ruteo de {@code PublicApiJwtFilter} sin arranque de Quarkus: las decisiones
 * viven en metodos estaticos puros ({@code decidir}, {@code conPrefijoRaiz},
 * {@code rutaRelativa}, {@code esRutaApi}), asi que se ejercitan con JUnit5 +
 * AssertJ, sin base de datos ni {@code @QuarkusTest}.
 *
 * <p><b>Que estaba roto.</b> El filtro comparaba contra el literal
 * {@code "/Mercurius/api/v1/"} y, si nada coincidia, hacia un {@code return}
 * mudo: cambiar {@code quarkus.http.root-path} — o un proxy que despoja el
 * prefijo — dejaba las 23 rutas de accounting/mercatus sin autenticar y sin una
 * sola linea de log. Estas pruebas fijan las dos propiedades:
 * la ruta de la API publica se reconoce con o sin prefijo raiz, y una ruta bajo
 * {@code /api/} desconocida se DENIEGA en vez de colarse.</p>
 *
 * <p><b>Lo que NO debe cambiar.</b> Los espacios {@code /api/app/**} y
 * {@code /api/marketplace/**} pertenecen a otro dominio de autenticacion
 * (form auth + {@code @RolesAllowed}); denegarlos devolveria 401 antes de que la
 * policy llegue a evaluarse y reventaria {@code RoleMatrixTest}. Las paginas,
 * estaticos y el login tampoco son rutas de API. Ambos grupos se fijan aqui
 * para que un futuro endurecimiento no los capture por accidente.</p>
 */
@DisplayName("PublicApiJwtFilter: ruteo, fail-closed y no-regresion")
class PublicApiJwtFilterRoutingTest {

    private static final String RAIZ = "/Mercurius";

    private static Decision decidir(String path) {
        return PublicApiJwtFilter.decidir(path, RAIZ);
    }

    // ── normalizacion de la raiz ──────────────────────────────────────────

    @Nested
    @DisplayName("normalizarRaiz")
    class NormalizarRaiz {

        @Test
        @DisplayName("quita la barra final y conserva la inicial")
        void normaliza() {
            assertThat(PublicApiJwtFilter.normalizarRaiz("/Mercurius")).isEqualTo("/Mercurius");
            assertThat(PublicApiJwtFilter.normalizarRaiz("/Mercurius/")).isEqualTo("/Mercurius");
            assertThat(PublicApiJwtFilter.normalizarRaiz("/Mercurius///")).isEqualTo("/Mercurius");
            assertThat(PublicApiJwtFilter.normalizarRaiz("Mercurius")).isEqualTo("/Mercurius");
        }

        @Test
        @DisplayName("una raiz vacia o raiz '/' equivale a sin prefijo")
        void raizVaciaOSlash() {
            assertThat(PublicApiJwtFilter.normalizarRaiz("/")).isEmpty();
            assertThat(PublicApiJwtFilter.normalizarRaiz("")).isEmpty();
            assertThat(PublicApiJwtFilter.normalizarRaiz(null)).isEmpty();
        }
    }

    // ── la API publica se reconoce ────────────────────────────────────────

    @Nested
    @DisplayName("la API publica sigue exigiendo token")
    class DominioPublico {

        @Test
        @DisplayName("las 23 rutas accounting/mercatus con prefijo raiz se autentican")
        void prefijadas() {
            assertThat(decidir("/Mercurius/api/v1/accounting/invoices"))
                    .isEqualTo(Decision.AUTENTICAR);
            assertThat(decidir("/Mercurius/api/v1/accounting/invoices/issued"))
                    .isEqualTo(Decision.AUTENTICAR);
            assertThat(decidir("/Mercurius/api/v1/accounting/cash-register"))
                    .isEqualTo(Decision.AUTENTICAR);
            assertThat(decidir("/Mercurius/api/v1/mercatus/orders"))
                    .isEqualTo(Decision.AUTENTICAR);
            assertThat(decidir("/Mercurius/api/v1/mercatus/articles"))
                    .isEqualTo(Decision.AUTENTICAR);
        }

        @Test
        @DisplayName("un proxy que despoja /Mercurius ya no esquiva el filtro")
        void sinPrefijoRaiz() {
            // Este es el agujero que se cierra: antes, sin el literal
            // "/Mercurius" adelante, la ruta caia en el return mudo.
            assertThat(decidir("/api/v1/accounting/invoices"))
                    .isEqualTo(Decision.AUTENTICAR);
            assertThat(decidir("/api/v1/mercatus/orders"))
                    .isEqualTo(Decision.AUTENTICAR);
        }

        @Test
        @DisplayName("conPrefijoRaiz sigue siendo startsWith sobre la raiz normalizada")
        void conPrefijoRaizEsEstricto() {
            assertThat(PublicApiJwtFilter.conPrefijoRaiz("/Mercurius/api/v1/x", RAIZ)).isTrue();
            // Raiz con barra final: la normalizacion evita el falso negativo.
            assertThat(PublicApiJwtFilter.conPrefijoRaiz("/Mercurius/api/v1/x", "/Mercurius/")).isTrue();
            // Sin la barra final NO es la ruta de la API (evita /api/v1xyz).
            assertThat(PublicApiJwtFilter.conPrefijoRaiz("/Mercurius/api/v1", RAIZ)).isFalse();
            assertThat(PublicApiJwtFilter.conPrefijoRaiz("/Mercurius/api/v10/x", RAIZ)).isFalse();
            // Un prefijo distinto no debe producir una coincidencia falsa.
            assertThat(PublicApiJwtFilter.conPrefijoRaiz("/Mercatus/api/v1/x", RAIZ)).isFalse();
        }

        @Test
        @DisplayName("cambiar quarkus.http.root-path no abre la API publica")
        void otraRaiz() {
            assertThat(PublicApiJwtFilter.decidir("/OtraRaiz/api/v1/accounting/invoices", "/OtraRaiz"))
                    .isEqualTo(Decision.AUTENTICAR);
            // Y una raiz que ya no corresponde tampoco deja pasar en silencio:
            // antes esta ruta caia en el return mudo.
            assertThat(PublicApiJwtFilter.decidir("/Mercurius/api/v1/accounting/invoices", "/OtraRaiz"))
                    .as("raiz desalineada: la ruta bajo /api/ no puede colarse")
                    .isEqualTo(Decision.DENEGAR);
        }

        @Test
        @DisplayName("los endpoints de analytics siguen exigiendo token")
        void analytics() {
            assertThat(decidir("/Mercurius/api/dashboard")).isEqualTo(Decision.AUTENTICAR);
            assertThat(decidir("/Mercurius/api/stock-forecast")).isEqualTo(Decision.AUTENTICAR);
            assertThat(decidir("/Mercurius/api/sales-trend")).isEqualTo(Decision.AUTENTICAR);
            assertThat(decidir("/Mercurius/api/product-performance")).isEqualTo(Decision.AUTENTICAR);
            assertThat(decidir("/Mercurius/api/quick-actions")).isEqualTo(Decision.AUTENTICAR);
        }

        @Test
        @DisplayName("las rutas exentas siguen exentas")
        void exentas() {
            assertThat(decidir("/Mercurius/oauth/token")).isEqualTo(Decision.EXENTO);
            assertThat(decidir("/oauth/token")).isEqualTo(Decision.EXENTO);
        }

        @Test
        @DisplayName("login y registro mercatus son alcanzables sin token")
        void credencialesMercatusExentas() {
            // Sin esto, pedir un token exigia ya tener un token (401):
            // login y registro eran inalcanzables en produccion.
            assertThat(decidir("/Mercurius/api/v1/mercatus/clients/auth/login"))
                    .isEqualTo(Decision.EXENTO);
            assertThat(decidir("/api/v1/mercatus/clients/auth/login"))
                    .isEqualTo(Decision.EXENTO);
            assertThat(decidir("/Mercurius/api/v1/mercatus/clients/register"))
                    .isEqualTo(Decision.EXENTO);
        }
    }

    // ── fail-closed ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("una ruta /api/ desconocida se deniega")
    class FailClosed {

        @Test
        @DisplayName("/api/desconocido cae en la rama de denegacion, no en el permiso mudo")
        void rutaDesconocidaSeDeniega() {
            assertThat(decidir("/Mercurius/api/desconocido"))
                    .as("antes esto hacia return sin autenticar; ahora debe DENEGAR")
                    .isEqualTo(Decision.DENEGAR);
            assertThat(decidir("/api/desconocido"))
                    .as("tambien sin prefijo raiz")
                    .isEqualTo(Decision.DENEGAR);
        }

        @ParameterizedTest(name = "{0} se deniega")
        @ValueSource(strings = {
            "/Mercurius/api/",
            "/Mercurius/api/v2/accounting/invoices",
            "/Mercurius/api/v10/mercatus/orders",
            "/Mercurius/api/contabilidad",
            "/Mercurius/api/app",
            "/Mercurius/api/marketplace",
            "/api/v2/cualquier-cosa"
        })
        void deniega(String path) {
            assertThat(decidir(path)).isEqualTo(Decision.DENEGAR);
        }

        @Test
        @DisplayName("esRutaApi busca /api/ como segmento completo, en cualquier prefijo")
        void esRutaApiPorSegmento() {
            assertThat(PublicApiJwtFilter.esRutaApi("/api")).isTrue();
            assertThat(PublicApiJwtFilter.esRutaApi("/api/desconocido")).isTrue();
            assertThat(PublicApiJwtFilter.esRutaApi("/Mercurius/api/v1/x")).isTrue();
            // /apidocs NO es el espacio /api/ (segmento, no substring).
            assertThat(PublicApiJwtFilter.esRutaApi("/Mercurius/apidocs")).isFalse();
            assertThat(PublicApiJwtFilter.esRutaApi("/Mercurius/app/cabys")).isFalse();
        }

        @Test
        @DisplayName("un estatico con un segmento /api/ se deniega en vez de servirse")
        void estaticoConApiEnElNombre() {
            // Trade-off deliberado del fail-closed: preferimos un falso
            // positivo ruidoso a un falso negativo de seguridad. Hoy no existe
            // ningun estatico con esa forma (ningun archivo de
            // src/main/resources cuelga de un directorio api/), asi que esto no
            // rompe la superficie publica.
            assertThat(PublicApiJwtFilter.esRutaApi("/static/js/api/app.js")).isTrue();
            assertThat(decidir("/Mercurius/static/js/api/app.js"))
                    .isEqualTo(Decision.DENEGAR);
        }
    }

    // ── no-regresion: lo que el filtro NO debe tocar ──────────────────────

    @Nested
    @DisplayName("lo que no es API no se ve afectado")
    class FueraDeAlcance {

        @ParameterizedTest(name = "{0} se permite tal cual")
        @ValueSource(strings = {
            "/Mercurius/login",
            "/Mercurius/app",
            "/Mercurius/app/cabys",
            "/Mercurius/static/bundle/app.js",
            "/Mercurius/resources/css/estilos.css",
            "/Mercurius/",
            "/Mercurius",
            "/secured/index.xhtml"
        })
        void noApiSePermite(String path) {
            assertThat(decidir(path)).isEqualTo(Decision.FUERA_DE_ALCANCE);
        }

        @Test
        @DisplayName("/api/app/** conserva su propio dominio de autenticacion")
        void apiAppIntacto() {
            // Este filtro nunca cubrio /api/app/**: lo protege la policy
            // secured + @RolesAllowed (RoleMatrixTest espera 200/403/302 aqui,
            // no 401).
            assertThat(decidir("/Mercurius/api/app/cabys")).isEqualTo(Decision.FUERA_DE_ALCANCE);
            assertThat(decidir("/Mercurius/api/app/auth/logout")).isEqualTo(Decision.FUERA_DE_ALCANCE);
            assertThat(decidir("/Mercurius/api/app/pos/cart")).isEqualTo(Decision.FUERA_DE_ALCANCE);
            assertThat(decidir("/Mercurius/api/app/settings")).isEqualTo(Decision.FUERA_DE_ALCANCE);
        }

        @Test
        @DisplayName("/api/marketplace/** conserva la conducta actual")
        void apiMarketplaceIntacto() {
            assertThat(decidir("/Mercurius/api/marketplace/cart")).isEqualTo(Decision.FUERA_DE_ALCANCE);
            assertThat(decidir("/Mercurius/api/marketplace/orders")).isEqualTo(Decision.FUERA_DE_ALCANCE);
            assertThat(decidir("/Mercurius/api/marketplace/auth")).isEqualTo(Decision.FUERA_DE_ALCANCE);
        }
    }
}
