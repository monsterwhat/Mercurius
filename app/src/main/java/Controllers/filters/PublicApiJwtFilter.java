package Controllers.filters;

import Models.ClientesApi;
import Services.ApiClientsService;
import Services.JwtTokenUtil;
import Utils.RateLimiter;
import io.jsonwebtoken.Claims;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * JWT authentication filter for the public REST API endpoints.
 * Intercepts all requests to /api/v1/** and analytics endpoints.
 * Validates Bearer tokens issued by OAuth2 client_credentials flow.
 *
 * <p>The root-path prefix comes from {@code quarkus.http.root-path} instead of a
 * hardcoded {@code "/Mercurius"} literal, and the routing decision
 * ({@link #decidir}) fails CLOSED: a path under {@code /api/} that matches no
 * rule is denied with a 401 and a loud {@code LOG.warn} instead of being
 * silently permitted. Before that, a changed root-path or a proxy stripping
 * the prefix left all 23 accounting/mercatus endpoints unauthenticated with no
 * log line at all.</p>
 *
 * <p>Los cuatro {@code abortWith} que llevan un cuerpo de error declaran
 * {@code .type(MediaType.APPLICATION_JSON)}. Sin eso JAX-RS serializa la
 * cadena con el default {@code text/plain}: el cuerpo era JSON pero
 * {@code Content-Type} decia otra cosa, y un cliente que decide como
 * parsear el error por el content type no lo parseaba. El mismo criterio
 * usa {@code MarketplaceJwtFilter} en sus aborts.</p>
 *
 * <p>Las exenciones se comparan por igualdad EXACTA sobre la ruta ya
 * normalizada ({@link #normalizarRuta}), no por {@code endsWith} ni por
 * {@code contains}: con esas dos, cualquier ruta que CONTUBIERA el literal
 * se saltaba la autenticacion, de modo que {@code /api/v1/x/oauth/token} o
 * {@code /api/v1/mercatus/clients/auth/login/extra} pasaban sin Bearer. Con
 * igualdad exacta, un segmento de mas hace que la ruta deje de ser la exenta y
 * caiga en la rama que le corresponde.</p>
 */
@Provider
@Priority(Priorities.AUTHENTICATION)
public class PublicApiJwtFilter implements ContainerRequestFilter {

    private static final Logger LOG = Logger.getLogger(PublicApiJwtFilter.class);
    private static final String OPTIONS_METHOD = "OPTIONS";

    // Paths that are exempt from auth (token endpoint, etc.)
    // Rutas COMPLETAS, no prefijos ni fragmentos: se comparan por igualdad
    // exacta contra la ruta normalizada (ver normalizarRuta). Una entrada se
    // escribe tal cual llega en el contenedor, con barra inicial y SIN el
    // prefijo raiz, para que sea obvio que no es un patron.
    private static final Set<String> EXEMPT_PATHS = Set.of(
        "/oauth/token",
        // Mercatus credential-issuing endpoints: requiring a Bearer token to
        // reach the endpoint that ISSUES tokens is a lockout (anonymous login
        // and registration answered 401). Throttling still applies inside the
        // controllers (IntentosDeCredencial), so exemption here is not
        // unguarded — it just moves the check to the layer that can tell
        // guessing apart from legitimate use.
        "/api/v1/mercatus/clients/auth/login",
        "/api/v1/mercatus/clients/register"
    );

    /**
     * Las mismas entradas de {@link #EXEMPT_PATHS} en forma canonica, porque
     * la comparacion es de igualdad exacta y los dos lados tienen que estar
     * igual de normalizados. Hoy los literales ya vienen normalizados, asi que
     * el resultado es identico a EXEMPT_PATHS; se calcula igual para que
     * anadir una entrada sin la barra inicial (o con el prefijo raiz) no
     * disables la exencion en silencio.
     */
    private static final Set<String> EXEMPT_PATHS_NORMALIZADOS = EXEMPT_PATHS.stream()
            .map(exempt -> normalizarRuta(exempt, ""))
            .collect(Collectors.toUnmodifiableSet());

    // Analytics endpoints that need auth but are NOT under /api/v1/
    private static final Set<String> ANALYTICS_PATHS = Set.of(
        "/api/stock-forecast",
        "/api/sales-trend",
        "/api/dashboard",
        "/api/product-performance",
        "/api/quick-actions"
    );

    // Sub-espacios de /api/ que PERTENECEN A OTRO DOMINIO DE AUTENTICACION y que
    // esta filtro nunca cubrio. Se enumeran de forma explicita (y no por
    // exclusion) para que el cierre por defecto de mas abajo no los capture:
    //
    //   /api/app/**         30 recursos de la app Qute/HTMX, protegidos por la
    //                       policy quarkus.http.auth.permission.secured +
    //                       @RolesAllowed (ver RoleMatrixTest). Si esta filtro
    //                       los denegara devolveria 401 antes de que la
    //                       policy llegue a evaluarse.
    //   /api/marketplace/** 5 recursos de Controllers.Api.Marketplace, protegidos
    //                       por SU PROPIO filtro (Controllers.filters.
    //                       MarketplaceJwtFilter, chequeo Bearer sobre rutas que
    //                       contienen /api/marketplace/) y no por esta filtro
    //                       ni por ninguna policy de application.properties.
    //                       Se respeta ese dominio: esta filtro los deja pasar
    //                       igual que antes.
    //
    // Lo que NO aparece aqui es lo que hace que el gate sea fail-CLOSED: una
    // ruta /api/ desconocida no coincide con ninguna de estas listas y por lo
    // tanto se deniega.
    private static final List<String> ESPACIOS_AJENOS = List.of(
        "/api/app/",
        "/api/marketplace/"
    );

    /**
     * Decision de ruteo del filtro, aislada de la capa HTTP para poder
     * probarse sin arrancar Quarkus.
     */
    enum Decision {
        /** Exenta por configuracion (p. ej. /oauth/token, preflight OPTIONS). */
        EXENTO,
        /** Fuera del dominio de esta filtro (paginas, estaticos, /api/app/**). */
        FUERA_DE_ALCANCE,
        /** Dominio de la API publica: hay que validar el Bearer. */
        AUTENTICAR,
        /** Fail-closed: bajo /api/ pero ninguna regla coincidio. */
        DENEGAR
    }

    @Inject
    @Nonnull
    JwtTokenUtil jwtTokenUtil;

    @Inject
    @Nonnull
    RateLimiter rateLimiter;

    @Inject
    @Nonnull
    ApiClientsService apiClientsService;

    /**
     * Prefijo de contexto de la aplicacion. Se inyecta en vez de escribir el
     * literal "/Mercurius" en el codigo: si quarkus.http.root-path cambia, el
     * {@code startsWith} se hace cargo solo. El default reproduce el valor de
     * application.properties para que el filtro no se rompa si la propiedad no
     * estuviera definida.
     */
    @ConfigProperty(name = "quarkus.http.root-path", defaultValue = "/Mercurius")
    String rootPath;

    // ── Decisiones de ruteo (puras, sin CDI: se prueban sin arranque) ────────

    /** Normaliza el root-path: siempre con barra inicial y sin barra final. */
    static String normalizarRaiz(String rootPath) {
        if (rootPath == null || rootPath.isBlank() || "/".equals(rootPath)) {
            return "";
        }
        String raiz = rootPath.startsWith("/") ? rootPath : "/" + rootPath;
        while (raiz.endsWith("/")) {
            raiz = raiz.substring(0, raiz.length() - 1);
        }
        return raiz;
    }

    /**
     * Quita el prefijo raiz si viene presente, dejando la ruta relativa a la
     * aplicacion. Si un proxy despojo el prefijo, la ruta YA llega relativa y
     * se devuelve tal cual — que es justamente el caso que antes se escapaba.
     */
    static String rutaRelativa(String path, String rootPath) {
        if (path == null) {
            return "";
        }
        String raiz = normalizarRaiz(rootPath);
        if (raiz.isEmpty()) {
            return path;
        }
        if (path.equals(raiz)) {
            return "/";
        }
        if (path.startsWith(raiz + "/")) {
            return path.substring(raiz.length());
        }
        return path;
    }

    /**
     * Forma canonica de una ruta para compararla contra las listas de la
     * filtro: relativa a la raiz (ver {@link #rutaRelativa}) y con barra
     * inicial garantizada. Una ruta vacia (o nula) se canoniza a {@code "/"}.
     *
     * <p>Es la unica normalizacion que se aplica: NO se quitan barras
     * finales ni se resuelve {@code .} / {@code ..} / dobles barras, para que
     * lo que se compara sea exactamente el camino quellego al contenedor. Por
     * eso la comparacion de exenciones puede ser de igualdad exacta sin
     * abrir la puerta a los prefijos.</p>
     */
    static String normalizarRuta(String path, String rootPath) {
        String relativa = rutaRelativa(path, rootPath);
        if (relativa.isEmpty()) {
            return "/";
        }
        return relativa.startsWith("/") ? relativa : "/" + relativa;
    }

    /**
     * Coincidencia historica: la ruta empieza por {@code <raiz>/api/v1/}.
     * Se conserva con {@code startsWith} (no {@code contains}) por seguridad.
     */
    static boolean conPrefijoRaiz(String path, String rootPath) {
        return path != null
                && path.startsWith(normalizarRaiz(rootPath) + "/api/v1/");
    }

    /**
     * true si la ruta pertenece al espacio de nombres {@code /api/}, buscando
     * {@code /api} como segmento COMPLETO (no un substring cualquiera) y en
     * cualquier nivel de prefijo.
     *
     * <p>Se busca en toda la ruta, no solo en la parte relativa a la raiz, a
     * proposito: si {@code quarkus.http.root-path} se cambia y la ruta sigue
     * llegando con el prefijo viejo, {@link #rutaRelativa} no lo puede quitar y
     * un gate anclado devolveria "fuera de alcance" — es decir, permitir en
     * silencio, justo lo que este cambio viene a cerrar.</p>
     *
     * <p>El trade-off es deliberado: un estatico con un segmento {@code /api/}
     * en su nombre se denegaria en vez de servirse. Hoy no existe ninguno (se
     * verifico que ningun archivo de src/main/resources cuelga de un directorio
     * {@code api/}), y un falso negativo de seguridad es peor que un falso
     * positivo ruidoso.</p>
     */
    static boolean esRutaApi(String path) {
        return path.contains("/api/") || path.endsWith("/api");
    }

    /** true si la ruta pertenece a un espacio /api/ ajeno a esta filtro. */
    static boolean esEspacioAjeno(String rutaRelativa) {
        return ESPACIOS_AJENOS.stream().anyMatch(rutaRelativa::startsWith);
    }

    /**
     * Decision unica de ruteo. Orden de evaluacion:
     * 1. exentas (igualdad exacta sobre la ruta normalizada); 2. dominio publico
     * (/api/v1/ con o sin prefijo raiz, y analytics); 3. fuera de alcance
     * (paginas, estaticos, espacios ajenos); 4. cualquier otra cosa bajo /api/
     * -> DENEGAR (fail-closed).
     */
    static Decision decidir(String path, String rootPath) {
        if (path == null) {
            return Decision.FUERA_DE_ALCANCE;
        }
        // Exenciones por igualdad EXACTA. Antes era endsWith || contains, que
        // concedia la exencion a CUALQUIER ruta que contuviera el literal:
        // /api/v1/x/oauth/token, /api/v1/mercatus/clients/auth/login/extra y
        // /api/v1/mercatus/clients/registerXYZ se saltaban la autenticacion.
        // Con igualdad exacta, la exencion se aplica a la ruta y solo a ella.
        if (EXEMPT_PATHS_NORMALIZADOS.contains(normalizarRuta(path, rootPath))) {
            return Decision.EXENTO;
        }
        if (conPrefijoRaiz(path, rootPath)) {
            return Decision.AUTENTICAR;
        }
        // Mismo dominio pero alcanzado sin el prefijo: si el proxy o el
        // despliegue despoja /Mercurius, /api/v1/... sigue siendo API publica.
        String relativa = rutaRelativa(path, rootPath);
        if (relativa.startsWith("/api/v1/")) {
            return Decision.AUTENTICAR;
        }
        for (String analyticsPath : ANALYTICS_PATHS) {
            if (path.contains(analyticsPath)) {
                return Decision.AUTENTICAR;
            }
        }
        if (!esRutaApi(path)) {
            return Decision.FUERA_DE_ALCANCE;
        }
        // Bajo /api/, pero de un dominio ajeno a esta filtro: se deja pasar
        // igual que antes (no es un silencio, esta enumerado aqui arriba).
        if (esEspacioAjeno(relativa) || esEspacioAjeno(path)) {
            return Decision.FUERA_DE_ALCANCE;
        }
        return Decision.DENEGAR;
    }

    @Override
    public void filter(@Nonnull ContainerRequestContext requestContext) throws IOException {
        // Skip auth for preflight CORS requests
        if (OPTIONS_METHOD.equalsIgnoreCase(requestContext.getMethod())) {
            return;
        }

        String path = requestContext.getUriInfo().getAbsolutePath().getPath();

        Decision decision = decidir(path, rootPath);

        // Skip auth for exempt paths and for anything outside this filter's domain
        if (decision == Decision.EXENTO || decision == Decision.FUERA_DE_ALCANCE) {
            return;
        }

        // Fail closed: a path under /api/ that matched no rule used to be
        // permitted silently, so a changed root-path (or a proxy stripping the
        // prefix) left the whole accounting/mercatus surface unauthenticated
        // with no trace. Deny loudly instead.
        if (decision == Decision.DENEGAR) {
            LOG.warnf(
                    "PublicApiJwtFilter: ruta bajo /api/ que no coincide con ninguna regla de "
                    + "autenticacion; se deniega por defecto (path=%s, root-path=%s). Si esta ruta "
                    + "debe ser publica, agreguela explicitamente a EXEMPT_PATHS o ESPACIOS_AJENOS.",
                    path, rootPath);
            requestContext.abortWith(
                    Response.status(Response.Status.UNAUTHORIZED)
                            .header("WWW-Authenticate", "Bearer error=\"invalid_token\"")
                            .type(MediaType.APPLICATION_JSON)
                            .entity("{\"error\":{\"code\":\"UNAUTHORIZED\",\"message\":\"Ruta de API no reconocida\"}}")
                            .build()
            );
            return;
        }

        // Extract Authorization header
        String authHeader = requestContext.getHeaderString(HttpHeaders.AUTHORIZATION);
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            LOG.debug("Missing or invalid Authorization header for: " + path);
            requestContext.abortWith(
                    Response.status(Response.Status.UNAUTHORIZED)
                            .header("WWW-Authenticate", "Bearer error=\"invalid_token\"")
                            .type(MediaType.APPLICATION_JSON)
                            .entity("{\"error\":{\"code\":\"UNAUTHORIZED\",\"message\":\"Token de autenticación requerido\"}}")
                            .build()
            );
            return;
        }

        String token = authHeader.substring("Bearer ".length()).trim();

        // Try API token first (from OAuth2 client_credentials)
        Claims claims = jwtTokenUtil.validateApiToken(token);
        if (claims != null) {
            String clientId = claims.getSubject();
            String scopeClaim = claims.get("scope", String.class);
            Set<String> scopes = scopeClaim != null ? Set.of(scopeClaim.split(" ")) : Set.of();

            // Set properties for downstream controllers
            requestContext.setProperty("apiClientId", clientId);
            requestContext.setProperty("apiScopes", scopes);
            requestContext.setProperty("tokenType", "api_access");

            // Check rate limits for API clients
            ClientesApi client = apiClientsService.findByClientId(clientId);
            if (client != null) {
                Long retryAfter = rateLimiter.checkRateLimit(clientId, client.getRateLimitPerMin(), client.getRateLimitPerHour());
                if (retryAfter != null) {
                    requestContext.abortWith(
                            Response.status(429)
                                    .header("Retry-After", String.valueOf(retryAfter))
                                    .type(MediaType.APPLICATION_JSON)
                                    .entity("{\"error\":{\"code\":\"RATE_LIMITED\",\"message\":\"Rate limit exceeded. Try again in " + retryAfter + " seconds.\"}}")
                                    .build()
                    );
                    return;
                }
            }

            requestContext.setSecurityContext(new jakarta.ws.rs.core.SecurityContext() {
                @Override
                public java.security.Principal getUserPrincipal() {
                    return () -> clientId;
                }
                @Override
                public boolean isUserInRole(String role) {
                    return scopes.contains(role);
                }
                @Override
                public boolean isSecure() {
                    return requestContext.getUriInfo().getAbsolutePath().toString().startsWith("https");
                }
                @Override
                public String getAuthenticationScheme() {
                    return "Bearer";
                }
            });
            return;
        }

        // Try Mercatus client token (existing marketplace JWT)
        Integer clientCode = jwtTokenUtil.validateAccessToken(token);
        if (clientCode != null) {
            // Mercatus client token — set client code for downstream
            requestContext.setProperty("mercatusClientCode", clientCode);
            requestContext.setProperty("tokenType", "mercatus_client");

            requestContext.setSecurityContext(new jakarta.ws.rs.core.SecurityContext() {
                @Override
                public java.security.Principal getUserPrincipal() {
                    return () -> String.valueOf(clientCode);
                }
                @Override
                public boolean isUserInRole(String role) { return false; }
                @Override
                public boolean isSecure() {
                    return requestContext.getUriInfo().getAbsolutePath().toString().startsWith("https");
                }
                @Override
                public String getAuthenticationScheme() {
                    return "Bearer";
                }
            });
            return;
        }

        // No valid token found
        LOG.debug("Invalid or expired JWT token for: " + path);
        requestContext.abortWith(
                Response.status(Response.Status.UNAUTHORIZED)
                        .header("WWW-Authenticate", "Bearer error=\"invalid_token\"")
                        .type(MediaType.APPLICATION_JSON)
                        .entity("{\"error\":{\"code\":\"UNAUTHORIZED\",\"message\":\"Token inválido o expirado\"}}")
                        .build()
        );
    }
}
