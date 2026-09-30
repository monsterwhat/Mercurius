package Controllers.filters;

import Models.DTO.ApiResponse;
import Services.JwtTokenUtil;
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
import org.jboss.logging.Logger;

/**
 * JWT authentication filter for Mercatus marketplace API endpoints.
 * Intercepts all requests to /api/marketplace/* except /api/marketplace/auth/*.
 * Expects a Bearer token in the Authorization header.
 */
@Provider
@Priority(Priorities.AUTHENTICATION)
public class MarketplaceJwtFilter implements ContainerRequestFilter {

    private static final Logger LOG = Logger.getLogger(MarketplaceJwtFilter.class);

    private static final String AUTH_PREFIX = "/api/marketplace/auth";
    private static final String OPTIONS_METHOD = "OPTIONS";

    /**
     * Los dos 401 de este filtro salen en el MISMO envelope
     * {@link ApiResponse} que los de los controladores de mercado, no en el
     * {@code {"error":"..."}} plano de antes: un cliente de
     * {@code /api/marketplace} no debe tener un lector para el rechazo del
     * token y otro para el del controlador.
     *
     * <p>El texto de ambos mensajes no cambia —es el que ya recibia esta
     * superficie—; lo que se agrega es {@code error.code}, que si distingue las
     * dos situaciones que el cliente debe tratar de forma distinta:
     * {@code UNAUTHORIZED} cuando no se presento ninguna credencial (hay que
     * pedirla) y {@code INVALID_TOKEN} cuando se presento un token y fue
     * rechazado, que es el mismo codigo que ya usa
     * {@code Controllers.Api.Marketplace.AuthController#refresh} para un token de
     * actualizacion rechazado. Antes esa distincion solo existia en el mensaje.</p>
     */
    @Nonnull
    private static final String MENSAJE_TOKEN_REQUERIDO = "Token de autenticación requerido";

    @Nonnull
    private static final String MENSAJE_TOKEN_INVALIDO = "Token inválido o expirado";

    @Inject
    @Nonnull
    JwtTokenUtil jwtTokenUtil;

    @Override
    public void filter(@Nonnull ContainerRequestContext requestContext) throws IOException {
        // Skip authentication for preflight CORS requests
        if (OPTIONS_METHOD.equalsIgnoreCase(requestContext.getMethod())) {
            return;
        }

        String path = requestContext.getUriInfo().getAbsolutePath().getPath();

        // Allow unauthenticated access to auth endpoints
        if (path.contains(AUTH_PREFIX)) {
            return;
        }

        // Only protect /api/marketplace/* endpoints
        if (!path.contains("/api/marketplace/")) {
            return;
        }

        // Extract Authorization header
        String authHeader = requestContext.getHeaderString(HttpHeaders.AUTHORIZATION);
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            LOG.debug("Missing or invalid Authorization header for: " + path);
            requestContext.abortWith(noAutenticado("UNAUTHORIZED", MENSAJE_TOKEN_REQUERIDO));
            return;
        }

        String token = authHeader.substring("Bearer ".length()).trim();
        Integer clientCode = jwtTokenUtil.validateAccessToken(token);

        if (clientCode == null) {
            LOG.debug("Invalid or expired JWT token for: " + path);
            requestContext.abortWith(noAutenticado("INVALID_TOKEN", MENSAJE_TOKEN_INVALIDO));
            return;
        }

        // Set client code as a property for downstream resources
        requestContext.setProperty("mercatusClientCode", clientCode);

        requestContext.setSecurityContext(new jakarta.ws.rs.core.SecurityContext() {
            @Override
            public java.security.Principal getUserPrincipal() {
                return () -> String.valueOf(clientCode);
            }
            @Override
            public boolean isUserInRole(String role) { return false; }
            @Override
            public boolean isSecure() { return requestContext.getUriInfo().getAbsolutePath().toString().startsWith("https"); }
            @Override
            public String getAuthenticationScheme() { return "Bearer"; }
        });
    }

/**
     * 401 con envelope {@link ApiResponse}.
     *
     * <p>El tipo de medio se fija explicito porque el cuerpo ya no es una cadena
     * con el JSON escrito a mano: un filtro que aborta no hereda el
     * {@code @Produces} del recurso —este filtro actua DESPUES del emparejamiento,
     * de modo que la negociacion con el recurso aun no ha ocurrido— y el
     * cliente merecia un tipo que describa lo que realmente lleva.</p>
     */
    @Nonnull
    private static Response noAutenticado(@Nonnull String codigo, @Nonnull String mensaje) {
        return Response.status(Response.Status.UNAUTHORIZED)
                .type(MediaType.APPLICATION_JSON)
                .entity(ApiResponse.error(codigo, mensaje))
                .build();
    }
}
