package Controllers.filters;

import Services.JwtTokenUtil;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import java.io.IOException;
import java.util.logging.Logger;

/**
 * JWT authentication filter for Mercatus marketplace API endpoints.
 * Intercepts all requests to /api/marketplace/* except /api/marketplace/auth/*.
 * Expects a Bearer token in the Authorization header.
 */
@Provider
@Priority(Priorities.AUTHENTICATION)
public class MarketplaceJwtFilter implements ContainerRequestFilter {

    private static final Logger LOG = Logger.getLogger(MarketplaceJwtFilter.class.getName());

    private static final String AUTH_PREFIX = "/api/marketplace/auth";
    private static final String OPTIONS_METHOD = "OPTIONS";

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
            LOG.fine("Missing or invalid Authorization header for: " + path);
            requestContext.abortWith(
                    Response.status(Response.Status.UNAUTHORIZED)
                            .entity("{\"error\":\"Token de autenticación requerido\"}")
                            .build()
            );
            return;
        }

        String token = authHeader.substring("Bearer ".length()).trim();
        Integer clientCode = jwtTokenUtil.validateAccessToken(token);

        if (clientCode == null) {
            LOG.fine("Invalid or expired JWT token for: " + path);
            requestContext.abortWith(
                    Response.status(Response.Status.UNAUTHORIZED)
                            .entity("{\"error\":\"Token inválido o expirado\"}")
                            .build()
            );
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
}
