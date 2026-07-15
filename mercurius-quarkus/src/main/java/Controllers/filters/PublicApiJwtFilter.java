package Controllers.filters;

import Models.ApiClients;
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
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import java.io.IOException;
import java.util.Set;
import java.util.logging.Logger;

/**
 * JWT authentication filter for the public REST API endpoints.
 * Intercepts all requests to /api/v1/** and analytics endpoints.
 * Validates Bearer tokens issued by OAuth2 client_credentials flow.
 */
@Provider
@Priority(Priorities.AUTHENTICATION)
public class PublicApiJwtFilter implements ContainerRequestFilter {

    private static final Logger LOG = Logger.getLogger(PublicApiJwtFilter.class.getName());
    private static final String OPTIONS_METHOD = "OPTIONS";

    // Paths that are exempt from auth (token endpoint, etc.)
    private static final Set<String> EXEMPT_PATHS = Set.of(
        "/oauth/token"
    );

    // Analytics endpoints that need auth but are NOT under /api/v1/
    private static final Set<String> ANALYTICS_PATHS = Set.of(
        "/api/stock-forecast",
        "/api/sales-trend",
        "/api/dashboard",
        "/api/product-performance",
        "/api/quick-actions"
    );

    @Inject
    @Nonnull
    JwtTokenUtil jwtTokenUtil;

    @Inject
    @Nonnull
    RateLimiter rateLimiter;

    @Inject
    @Nonnull
    ApiClientsService apiClientsService;

    @Override
    public void filter(@Nonnull ContainerRequestContext requestContext) throws IOException {
        // Skip auth for preflight CORS requests
        if (OPTIONS_METHOD.equalsIgnoreCase(requestContext.getMethod())) {
            return;
        }

        String path = requestContext.getUriInfo().getAbsolutePath().getPath();

        // Skip auth for exempt paths
        for (String exempt : EXEMPT_PATHS) {
            if (path.endsWith(exempt) || path.contains(exempt)) {
                return;
            }
        }

        // Determine if this path needs auth
        boolean needsAuth = false;

        // Check /api/v1/ prefix (use startsWith for security — not contains)
        // The root path is /Mercurius, so absolute path starts with /Mercurius/api/v1/
        if (path.startsWith("/Mercurius/api/v1/")) {
            needsAuth = true;
        }

        // Check analytics paths
        if (!needsAuth) {
            for (String analyticsPath : ANALYTICS_PATHS) {
                if (path.contains(analyticsPath)) {
                    needsAuth = true;
                    break;
                }
            }
        }

        if (!needsAuth) {
            return;
        }

        // Extract Authorization header
        String authHeader = requestContext.getHeaderString(HttpHeaders.AUTHORIZATION);
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            LOG.fine("Missing or invalid Authorization header for: " + path);
            requestContext.abortWith(
                    Response.status(Response.Status.UNAUTHORIZED)
                            .header("WWW-Authenticate", "Bearer error=\"invalid_token\"")
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
            ApiClients client = apiClientsService.findByClientId(clientId);
            if (client != null) {
                Long retryAfter = rateLimiter.checkRateLimit(clientId, client.getRateLimitPerMin(), client.getRateLimitPerHour());
                if (retryAfter != null) {
                    requestContext.abortWith(
                            Response.status(429)
                                    .header("Retry-After", String.valueOf(retryAfter))
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
        LOG.fine("Invalid or expired JWT token for: " + path);
        requestContext.abortWith(
                Response.status(Response.Status.UNAUTHORIZED)
                        .header("WWW-Authenticate", "Bearer error=\"invalid_token\"")
                        .entity("{\"error\":{\"code\":\"UNAUTHORIZED\",\"message\":\"Token inválido o expirado\"}}")
                        .build()
        );
    }
}
