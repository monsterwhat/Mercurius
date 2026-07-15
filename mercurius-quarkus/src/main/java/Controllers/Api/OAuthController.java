package Controllers.Api;

import Models.ApiClients;
import Models.DTO.ApiResponse;
import Services.ApiClientsService;
import Services.JwtTokenUtil;
import jakarta.annotation.Nonnull;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Form;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.HashSet;
import java.util.Set;
import java.util.logging.Logger;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * OAuth2 token endpoint implementing client_credentials grant type.
 * Used by system-to-system integrations (Mercatus, Accounting App).
 */
@Path("/oauth")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_FORM_URLENCODED)
@Tag(name = "Auth")
public class OAuthController {

    private static final Logger LOG = Logger.getLogger(OAuthController.class.getName());

    @Inject
    @Nonnull
    ApiClientsService apiClientsService;

    @Inject
    @Nonnull
    JwtTokenUtil jwtTokenUtil;

    @POST
    @Path("/token")
    @Operation(summary = "Exchange client credentials for JWT token")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Token issued"),
        @APIResponse(responseCode = "401", description = "Invalid credentials"),
        @APIResponse(responseCode = "500", description = "Internal server error")
    })
    public Response token(@Nonnull Form form) {
        String grantType = form.asMap().getFirst("grant_type");
        String clientId = form.asMap().getFirst("client_id");
        String clientSecret = form.asMap().getFirst("client_secret");
        String scopeParam = form.asMap().getFirst("scope");

        if (grantType == null || !grantType.equals("client_credentials")) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(ApiResponse.error("INVALID_GRANT", "Only grant_type=client_credentials is supported"))
                    .build();
        }

        if (clientId == null || clientId.isBlank() || clientSecret == null || clientSecret.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(ApiResponse.error("INVALID_REQUEST", "client_id and client_secret are required"))
                    .build();
        }

        ApiClients client = apiClientsService.findByClientId(clientId);
        if (client == null) {
            LOG.fine("OAuth token request failed: unknown client_id");
            return Response.status(Response.Status.UNAUTHORIZED)
                    .entity(ApiResponse.error("INVALID_CLIENT", "Unknown client_id"))
                    .build();
        }

        if (!apiClientsService.verifySecret(clientSecret, client.getClientSecret())) {
            LOG.fine("OAuth token request failed: invalid client_secret for " + clientId);
            return Response.status(Response.Status.UNAUTHORIZED)
                    .entity(ApiResponse.error("INVALID_CLIENT", "Invalid client_secret"))
                    .build();
        }

        Set<String> grantedScopes = parseScopes(scopeParam, client.getScopes());

        String accessToken = jwtTokenUtil.generateApiAccessToken(clientId, grantedScopes);
        long expiresIn = 15 * 60;

        OAuthTokenResponse tokenResponse = new OAuthTokenResponse();
        tokenResponse.setAccessToken(accessToken);
        tokenResponse.setTokenType("Bearer");
        tokenResponse.setExpiresIn(expiresIn);
        tokenResponse.setScope(String.join(" ", grantedScopes));

        LOG.info("OAuth token issued for client: " + clientId + " scopes: " + grantedScopes);
        return Response.ok(tokenResponse).build();
    }

    private Set<String> parseScopes(String requestedScopes, String allowedScopesJson) {
        Set<String> allowed = parseScopesJson(allowedScopesJson);
        if (requestedScopes == null || requestedScopes.isBlank()) {
            return allowed;
        }
        Set<String> requested = Set.of(requestedScopes.trim().split("\\s+"));
        Set<String> granted = new HashSet<>(requested);
        granted.retainAll(allowed);
        return granted.isEmpty() ? allowed : granted;
    }

    private Set<String> parseScopesJson(String scopesJson) {
        if (scopesJson == null || scopesJson.isBlank()) {
            return Set.of();
        }
        String cleaned = scopesJson.replaceAll("[\\[\\]\"]", "").trim();
        if (cleaned.isEmpty()) {
            return Set.of();
        }
        return Set.of(cleaned.split(",\\s*"));
    }

    public static class OAuthTokenResponse {
        private String accessToken;
        private String tokenType;
        private long expiresIn;
        private String scope;

        public OAuthTokenResponse() {}

        public String getAccessToken() { return accessToken; }
        public void setAccessToken(String accessToken) { this.accessToken = accessToken; }
        public String getTokenType() { return tokenType; }
        public void setTokenType(String tokenType) { this.tokenType = tokenType; }
        public long getExpiresIn() { return expiresIn; }
        public void setExpiresIn(long expiresIn) { this.expiresIn = expiresIn; }
        public String getScope() { return scope; }
        public void setScope(String scope) { this.scope = scope; }
    }
}
