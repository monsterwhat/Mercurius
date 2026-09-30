package Controllers.filters;

import static org.assertj.core.api.Assertions.assertThat;

import Controllers.filters.PublicApiJwtFilter.Decision;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
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
 *
 * <p><b>Lo que este archivo endurece.</b> Las exenciones se comparaban con
 * {@code endsWith || contains}, o sea que el literal bastaba: cualquier ruta
 * que lo CONTUVIERA se saltaba la autenticacion, tanto
 * {@code /api/v1/x/oauth/token} como
 * {@code /api/v1/mercatus/clients/auth/login/extra}. Ahora la exencion se
 * concede por igualdad EXACTA contra la ruta normalizada
 * ({@code normalizarRuta}: sin el prefijo raiz, con barra inicial), de modo que
 * un segmento de mas convierte la ruta en OTRA ruta y esta cae en la rama que le
 * corresponda — {@code AUTENTICAR} o {@code DENEGAR}, nunca {@code EXENTO}.</p>
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

    // ── normalizacion de la ruta que se compara ────────────────────────────

    @Nested
    @DisplayName("normalizarRuta")
    class NormalizarRuta {

        @Test
        @DisplayName("quita el prefijo raiz y exige barra inicial")
        void normaliza() {
            assertThat(PublicApiJwtFilter.normalizarRuta("/Mercurius/oauth/token", RAIZ))
                    .isEqualTo("/oauth/token");
            assertThat(PublicApiJwtFilter.normalizarRuta("/oauth/token", RAIZ))
                    .isEqualTo("/oauth/token");
            // Una ruta que llega sin barra inicial es comparable igual: la
            // barra se garantiza antes de mirar el conjunto de exenciones.
            assertThat(PublicApiJwtFilter.normalizarRuta("oauth/token", RAIZ))
                    .isEqualTo("/oauth/token");
            assertThat(PublicApiJwtFilter.normalizarRuta("/Mercurius/oauth/token", "/Mercurius/"))
                    .isEqualTo("/oauth/token");
        }

        @Test
        @DisplayName("una raiz vacia deja la ruta como venia")
        void sinPrefijo() {
            assertThat(PublicApiJwtFilter.normalizarRuta("/oauth/token", "/")).isEqualTo("/oauth/token");
            assertThat(PublicApiJwtFilter.normalizarRuta("/oauth/token", "")).isEqualTo("/oauth/token");
            assertThat(PublicApiJwtFilter.normalizarRuta("/oauth/token", null)).isEqualTo("/oauth/token");
        }

        @Test
        @DisplayName("una raiz que no corresponde no se toca")
        void raizAjena() {
            assertThat(PublicApiJwtFilter.normalizarRuta("/OtraRaiz/oauth/token", RAIZ))
                    .isEqualTo("/OtraRaiz/oauth/token");
            // Solo el segmento completo es prefijo: /Mercatus no es /Mercurius.
            assertThat(PublicApiJwtFilter.normalizarRuta("/Mercatus/oauth/token", RAIZ))
                    .isEqualTo("/Mercatus/oauth/token");
        }

        @Test
        @DisplayName("la ruta vacia o nula se canoniza a /")
        void vaciaONula() {
            assertThat(PublicApiJwtFilter.normalizarRuta("/Mercurius", RAIZ)).isEqualTo("/");
            assertThat(PublicApiJwtFilter.normalizarRuta("/Mercurius/", RAIZ)).isEqualTo("/");
            assertThat(PublicApiJwtFilter.normalizarRuta("", RAIZ)).isEqualTo("/");
            assertThat(PublicApiJwtFilter.normalizarRuta(null, RAIZ)).isEqualTo("/");
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

    // ── exenciones por igualdad exacta ────────────────────────────────────

    @Nested
    @DisplayName("las exenciones se conceden solo a la ruta exacta")
    class ExencionesExactas {

        /**
         * Las tres rutas exentas, tal como llegaban antes de endurecer la
         * comparacion, en cada forma de root-path que el despliegue puede usar.
         */
        static Stream<Arguments> rutasExentasPorDefecto() {
            return Stream.of(
                Arguments.of("/Mercurius/oauth/token", RAIZ),
                Arguments.of("/oauth/token", RAIZ),
                Arguments.of("oauth/token", RAIZ),
                Arguments.of("/Mercurius/oauth/token", "/Mercurius/"),
                Arguments.of("/oauth/token", "/"),
                Arguments.of("/oauth/token", ""),
                Arguments.of("/OtraRaiz/oauth/token", "/OtraRaiz"),
                Arguments.of("/Mercatus/api/v1/mercatus/clients/register", "/Mercatus"),
                Arguments.of("/Mercurius/api/v1/mercatus/clients/auth/login", RAIZ),
                Arguments.of("/api/v1/mercatus/clients/auth/login", RAIZ),
                Arguments.of("/api/v1/mercatus/clients/auth/login", "/"),
                Arguments.of("/api/v1/mercatus/clients/register", RAIZ),
                Arguments.of("/Mercurius/api/v1/mercatus/clients/register", RAIZ)
            );
        }

        @ParameterizedTest(name = "{0} ya no se exime")
        @ValueSource(strings = {
            // El literal aparece a mitad de camino: con contains bastaba.
            "/Mercurius/api/v1/oauth/token",
            "/api/v1/oauth/token",
            "/Mercurius/api/v1/x/oauth/token",
            "/api/v1/x/oauth/token",
            "/Mercurius/api/v2/x/oauth/token",
            "/api/v2/x/oauth/token",
            "/Mercurius/api/x/oauth/token",
            // La ruta exenta con segmentos de mas: ya no es la ruta exenta.
            "/Mercurius/api/v1/mercatus/clients/auth/login/extra",
            "/api/v1/mercatus/clients/auth/login/extra",
            "/Mercurius/api/v1/mercatus/clients/register/extra",
            "/api/v1/mercatus/clients/register/extra",
            // Y la ruta exenta pegada a un sufijo mas largo (registerXYZ,
            // loginXYZ): contains tambien las cubria, por eso eran EXENTO.
            "/Mercurius/api/v1/mercatus/clients/registerXYZ",
            "/api/v1/mercatus/clients/registerXYZ",
            "/Mercurius/api/v1/mercatus/clients/auth/loginXYZ"
        })
        void laRutaNoEsLaExenta(String path) {
            // Todas estas viven bajo /api/, asi que la rama que les toca es
            // AUTENTICAR (si son de la API publica) o DENEGAR (si son
            // desconocidas). Nunca EXENTO: la exencion no viaja por substring.
            assertThat(decidir(path))
                    .as("contener el literal, o alargarlo, ya no concede la exencion")
                    .isIn(Decision.AUTENTICAR, Decision.DENEGAR);
        }

        @Test
        @DisplayName("las de la API publica con un segmento de mas exigen Bearer")
        void segmentosDeMasEnLaApiPublica() {
            assertThat(decidir("/Mercurius/api/v1/mercatus/clients/auth/login/extra"))
                    .isEqualTo(Decision.AUTENTICAR);
            assertThat(decidir("/Mercurius/api/v1/mercatus/clients/register/extra"))
                    .isEqualTo(Decision.AUTENTICAR);
            assertThat(decidir("/Mercurius/api/v1/x/oauth/token"))
                    .isEqualTo(Decision.AUTENTICAR);
            // Fuera de /api/v1/ no hay recurso: el fail-closed lo deniega.
            assertThat(decidir("/Mercurius/api/v2/x/oauth/token"))
                    .isEqualTo(Decision.DENEGAR);
        }

        @Test
        @DisplayName("fuera de /api/ un segmento de mas tampoco es EXENTO")
        void segmentosDeMasFueraDeApi() {
            // Estas ni siquiera son dominio de la filtro (no viven bajo /api/),
            // asi que el resultado es FUERA_DE_ALCANCE. Lo que se fija es que la
            // exencion por fragmento desaparecio: antes estas eran EXENTO.
            assertThat(decidir("/Mercurius/oauth/token/extra")).isEqualTo(Decision.FUERA_DE_ALCANCE);
            assertThat(decidir("/oauth/token/extra")).isEqualTo(Decision.FUERA_DE_ALCANCE);
        }

        @ParameterizedTest(name = "{0} sigue exenta (root-path={1})")
        @MethodSource("rutasExentasPorDefecto")
        void lasDeAntesSiguenExentas(String path, String rootPath) {
            // La normalizacion no puede haber estrechado la superficie exenta:
            // con o sin prefijo raiz, las tres rutas siguen siendo alcanzables
            // sin token (login y register emitirian, si no, un 401).
            assertThat(PublicApiJwtFilter.decidir(path, rootPath))
                    .as("ruta exenta por configuracion")
                    .isEqualTo(Decision.EXENTO);
        }

        @Test
        @DisplayName("una exenta que llega con un prefijo que NO es la raiz tampoco se exime")
        void exentaConPrefijoAjeno() {
            // La exencion se resuelve sobre la ruta RELATIVA a la raiz, asi que
            // un prefijo desalineado no alcanza para obtenerla: la ruta cae en
            // el fail-closed de /api/ en vez de colarse.
            assertThat(PublicApiJwtFilter.decidir("/Mercurius/api/v1/mercatus/clients/auth/login", "/OtraRaiz"))
                    .isEqualTo(Decision.DENEGAR);
            // /oauth/token sin /api/ no es dominio de la filtro: lo relevante
            // es que deje de ser EXENTO (antes lo era por contains).
            assertThat(PublicApiJwtFilter.decidir("/Mercurius/oauth/token", "/OtraRaiz"))
                    .isNotEqualTo(Decision.EXENTO);
        }
    }

    // ── decisiones documentadas de la normalizacion ────────────────────────

    @Nested
    @DisplayName("caja y barra final: la comparacion es exacta y sin normalizar de mas")
    class CasosDocumentados {

        @Test
        @DisplayName("la coincidencia distingue mayusculas, como JAX-RS y el resto del ruteo")
        void cajaDistintaNoExime() {
            // Decision documentada: la comparacion es case-SENSITIVE. Es lo que
            // ya hacen conPrefijoRaiz y esRutaApi, y lo que hace el propio
            // JAX-RS al emparejar @Path: una variante en otra caja no es un
            // recurso registrado, asi que eximirla no aportaria nada y solo
            // abriria la puerta a que una variante tipografica quedara sin
            // cubrir.
            assertThat(decidir("/Mercurius/OAuth/Token")).isNotEqualTo(Decision.EXENTO);
            assertThat(decidir("/Mercurius/oauth/Token")).isNotEqualTo(Decision.EXENTO);
            assertThat(decidir("/Mercurius/api/v1/mercatus/clients/auth/LOGIN"))
                    .isNotEqualTo(Decision.EXENTO);
        }

        @Test
        @DisplayName("/api en mayusculas no es la version registrada de la ruta exenta")
        void apiEnMayusculasNoEsLaExenta() {
            // /api/v1 en mayusculas tampoco es la ruta registrada, y como
            // esRutaApi tambien distingue mayusculas, /API/V1/... ni entra al
            // espacio /api/. Lo que se fija es que no se concede la exencion.
            assertThat(decidir("/Mercurius/api/V1/mercatus/clients/auth/login"))
                    .isEqualTo(Decision.DENEGAR);
            assertThat(decidir("/Mercurius/API/V1/mercatus/clients/auth/login"))
                    .isNotEqualTo(Decision.EXENTO);
        }

        @Test
        @DisplayName("la barra final no se normaliza")
        void barraFinalNoSeNormaliza() {
            // Decision documentada: normalizar la ruta NO incluye quitar la
            // barra final. Con endsWith la barra final ya rompia la coincidencia
            // (asi que /oauth/token/ tampoco era EXENTO antes), y en la API
            // publica es irrelevante porque manda el prefijo /api/v1/.
            assertThat(decidir("/Mercurius/oauth/token/")).isEqualTo(Decision.FUERA_DE_ALCANCE);
            assertThat(decidir("/Mercurius/api/v1/mercatus/clients/auth/login/"))
                    .as("no es la ruta exenta: es la API publica, con Bearer")
                    .isEqualTo(Decision.AUTENTICAR);
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
