package Controllers.Api.App;

import Models.DTO.ApiResponse;
import Models.Users;
import Services.LoginService;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.vertx.http.runtime.security.FormAuthenticationMechanism;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import org.jboss.logging.Logger;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * Authentication endpoints for the NEW Qute/HTMX app surface (/app world).
 *
 * <p>Once T14/T15 enable the form-cookie auth block in application.properties,
 * every path under {@code /api/app/*} requires an authenticated user
 * (declarative policy), so all three endpoints here implicitly require login.</p>
 *
 * <p><b>Supervisor re-authorization</b> preserves the legacy behavior of
 * {@code SessionController.authorizeAction(username, password)} as an explicit
 * REST endpoint for sensitive-action confirmation (devoluciones, price
 * overrides, ...). The delegation is reimplemented against
 * {@link LoginService} directly because {@code SessionController} is a
 * {@code @SessionScoped} JSF-bound bean and must not be injected into JAX-RS
 * resources. Interactive (browser) login itself is handled by the custom
 * IdentityProvider (task T12); this resource only performs the explicit
 * re-check, so it stays fully decoupled from that layer.</p>
 *
 * <p>All responses follow the {@link ApiResponse} envelope conventions.</p>
 */
@Path("/api/app/auth")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "App - Auth")
public class AppAuthResource {

    private static final Logger LOG = Logger.getLogger(AppAuthResource.class);

    /**
     * Role tokens derived from {@link Users#getGroupName()} substrings,
     * mirroring SessionController's checks ({@code groupName.contains(token)});
     * a group containing "admin" implies every other role.
     */
    private static final List<String> ROLE_TOKENS =
            List.of("admin", "facturacion", "inventario", "usuario", "tributacion", "registro");

    @Nonnull
    @Inject
    LoginService loginService;

    @Inject
    @Nonnull
    SecurityIdentity securityIdentity;

    /**
     * Supervisor re-authorization: verifies a second set of credentials for a
     * sensitive action without ending the caller's own session.
     */
    @POST
    @Path("/supervisor-authorize")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Operation(summary = "Re-authorize a supervisor by verifying a second set of credentials")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Supervisor credentials verified"),
        @APIResponse(responseCode = "401", description = "Invalid credentials or disabled user"),
        @APIResponse(responseCode = "500", description = "Internal server error")
    })
    public Response supervisorAuthorize(
            @FormParam("username") @Nullable String username,
            @FormParam("password") @Nullable String password) {

        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            return invalidCredentials();
        }

        try {
            // Mirrors SessionController.authorizeAction(): lookup + BCrypt verify,
            // delegating persistence concerns to LoginService (findByUsername
            // already filters status = true; the explicit check below keeps the
            // disabled-user contract obvious and null-safe).
            Users authUser = loginService.findByUsername(username);
            if (authUser == null) {
                LOG.info("failed to supervisor authorize");
                return invalidCredentials();
            }

            if (!Boolean.TRUE.equals(authUser.getStatus())) {
                LOG.info("failed to supervisor authorize");
                return invalidCredentials();
            }

            if (!loginService.verifyPassword(password, authUser.getPassword())) {
                LOG.info("failed to supervisor authorize");
                return invalidCredentials();
            }

            return Response.ok(ApiResponse.ok(
                    new SupervisorAuthorizationDTO(username, deriveRoles(authUser)))).build();
        } catch (RuntimeException e) {
            LOG.warn("failed to supervisor authorize", e);
            LOG.warn("Error during supervisor authorization", e);
            return Response.serverError()
                    .entity(ApiResponse.error("INTERNAL_ERROR", "Error durante la autorización"))
                    .build();
        }
    }

    /**
     * Logout for the form-cookie auth world: clears the encrypted form-auth
     * session cookie server-side and redirects the browser back to the login
     * page.
     *
     * <p>Implementation note: quarkus-rest is not servlet-based, so injecting
     * {@code HttpServletRequest} is not supported here; the platform-supported
     * way to invalidate form-auth state is the documented static helper
     * {@link FormAuthenticationMechanism#logout(SecurityIdentity)}, driven by
     * the current {@link SecurityIdentity}.</p>
     */
    @POST
    @Path("/logout")
    // The navbar form and the CSRF form-field channel both submit
    // application/x-www-form-urlencoded; the class-level JSON @Consumes would
    // otherwise answer 415 to the endpoint's primary caller.
    @Consumes({MediaType.APPLICATION_JSON, MediaType.APPLICATION_FORM_URLENCODED})
    @Operation(summary = "Invalidate the form-auth session and redirect to the login page")
    @APIResponses({
        @APIResponse(responseCode = "303", description = "Session invalidated; redirect to /login"),
        @APIResponse(responseCode = "500", description = "Internal server error")
    })
    public Response logout(@Context UriInfo uriInfo) {
        if (securityIdentity != null && !securityIdentity.isAnonymous()) {
            FormAuthenticationMechanism.logout(securityIdentity);
        }
        URI loginUri = uriInfo.getBaseUriBuilder().path("login").build();
        return Response.seeOther(loginUri).build();
    }

    /**
     * Returns the current authenticated principal and its roles, read straight
     * from the Quarkus {@link SecurityIdentity}.
     */
    @GET
    @Path("/me")
    @Operation(summary = "Return the current principal and its roles")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Current user info"),
        @APIResponse(responseCode = "401", description = "No authenticated identity")
    })
    public Response me() {
        if (securityIdentity == null || securityIdentity.isAnonymous()
                || securityIdentity.getPrincipal() == null) {
            return Response.status(Response.Status.UNAUTHORIZED)
                    .entity(ApiResponse.error("UNAUTHENTICATED",
                            "No hay una sesión autenticada activa"))
                    .build();
        }
        CurrentUserDTO dto = new CurrentUserDTO(
                securityIdentity.getPrincipal().getName(),
                List.copyOf(securityIdentity.getRoles()));
        return Response.ok(ApiResponse.ok(dto)).build();
    }

    /**
     * Derives the role list from the user's groupName using the same substring
     * semantics as SessionController ("admin" implies every other role).
     */
    private List<String> deriveRoles(@Nonnull Users user) {
        String groupName = user.getGroupName() == null ? "" : user.getGroupName().toLowerCase();
        boolean isAdmin = groupName.contains("admin");
        List<String> roles = new ArrayList<>(ROLE_TOKENS.size());
        for (String token : ROLE_TOKENS) {
            if (groupName.contains(token) || (isAdmin && !"admin".equals(token))) {
                roles.add(token);
            }
        }
        return roles;
    }

    private Response invalidCredentials() {
        return Response.status(Response.Status.UNAUTHORIZED)
                .entity(ApiResponse.error("INVALID_CREDENTIALS",
                        "Usuario o contraseña incorrectos"))
                .build();
    }

    /** Success payload of supervisor-authorize: who authorized and with which roles. */
    public static class SupervisorAuthorizationDTO {
        public String authorizedBy;
        public List<String> roles;

        public SupervisorAuthorizationDTO(String authorizedBy, List<String> roles) {
            this.authorizedBy = authorizedBy;
            this.roles = roles;
        }
    }

    /** Payload of GET /me: current principal name plus effective roles. */
    public static class CurrentUserDTO {
        public String username;
        public List<String> roles;

        public CurrentUserDTO(String username, List<String> roles) {
            this.username = username;
            this.roles = roles;
        }
    }
}
