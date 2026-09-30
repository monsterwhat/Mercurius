package Controllers.Api.Mercatus;

import Models.DTO.ApiResponse;
import Models.DTO.AuthResponse;
import Models.DTO.LoginRequest;
import Models.DTO.RegisterRequest;
import Services.ClientAuthService;
import io.vertx.ext.web.RoutingContext;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * Mercatus client authentication endpoints.
 * Public: client registration and login for marketplace access.
 */
@Path("/api/v1/mercatus/clients")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Mercatus - Client Auth")
public class ClientAuthController {

    private static final Logger LOG = Logger.getLogger(ClientAuthController.class);

    @Inject
    @Nonnull
    ClientAuthService clientAuthService;

    /** Bounds password guessing against {@link #login(LoginRequest)}. */
    @Nonnull
    @Inject
    Utils.IntentosDeCredencial intentosDeCredencial;

    /** Source address for throttling; see {@link #direccionOrigen()}. */
    @Inject
    @Nullable
    RoutingContext routing;

    @POST
    @Path("/register")
    @Operation(summary = "Register a new marketplace client account")
    @APIResponses({
        @APIResponse(responseCode = "201", description = "Account created"),
        @APIResponse(responseCode = "409", description = "Validation error"),
        @APIResponse(responseCode = "500", description = "Internal server error")
    })
    public Response register(@Nonnull RegisterRequest request) {
        try {
            AuthResponse authResponse = clientAuthService.register(request);
            return Response.status(Response.Status.CREATED)
                    .entity(authResponse)
                    .build();
        } catch (IllegalArgumentException e) {
            LOG.info("Registration failed: " + e.getMessage());
            return Response.status(Response.Status.CONFLICT)
                    .entity(ApiResponse.error("VALIDATION_ERROR", e.getMessage()))
                    .build();
        } catch (Exception e) {
            LOG.warn("Registration error: " + e.getMessage());
            return Response.serverError()
                    .entity(ApiResponse.error("INTERNAL_ERROR", "Registration failed"))
                    .build();
        }
    }

    /**
     * Login: verifies a BCrypt hash for an <em>arbitrary, caller-supplied</em>
     * email, so unthrottled it was a credential-stuffing oracle.
     *
     * <p>The budget check lives here, before the verification, so a blocked key
     * is refused without spending any BCrypt CPU; the accounting for the
     * individual outcomes lives in {@link ClientAuthService#login}, which is the
     * only place that knows which branch rejected and therefore has to pay the
     * equalization cost. Both sides key the limiter on the same raw email,
     * which {@code IntentosDeCredencial.claveCuenta} normalizes identically.</p>
     */
    @POST
    @Path("/auth/login")
    @Operation(summary = "Login with email and password to get JWT token")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Login successful"),
        @APIResponse(responseCode = "401", description = "Invalid credentials"),
        @APIResponse(responseCode = "429", description = "Too many attempts; retry after the indicated delay"),
        @APIResponse(responseCode = "500", description = "Internal server error")
    })
    public Response login(@Nonnull LoginRequest request) {
        String direccion = direccionOrigen();

        Long bloqueo = intentosDeCredencial.restanteBloqueo(request.getEmail(), direccion);
        if (bloqueo != null) {
            return demasiadosIntentos(bloqueo);
        }

        try {
            AuthResponse authResponse = clientAuthService.login(request, direccion);
            return Response.ok(authResponse).build();
        } catch (IllegalArgumentException e) {
            LOG.info("Login failed: " + e.getMessage());
            return Response.status(Response.Status.UNAUTHORIZED)
                    .entity(ApiResponse.error("INVALID_CREDENTIALS", e.getMessage()))
                    .build();
        } catch (Exception e) {
            LOG.warn("Login error: " + e.getMessage());
            return Response.serverError()
                    .entity(ApiResponse.error("INTERNAL_ERROR", "Login failed"))
                    .build();
        }
    }

    /**
     * Best-effort source address for throttling.
     *
     * <p>Falls back to a constant when it cannot be determined. That is
     * deliberately NOT the empty string: an empty fallback would give every
     * unknown caller the same bucket, so one attacker could lock every user out
     * by tripping a shared counter. See
     * {@code AppAuthResource.direccionOrigen()}.</p>
     */
    private String direccionOrigen() {
        try {
            if (routing != null) {
                String ip = routing.request().remoteAddress() != null
                        ? routing.request().remoteAddress().hostAddress()
                        : null;
                if (ip != null && !ip.isBlank()) {
                    return ip;
                }
            }
        } catch (RuntimeException e) {
            LOG.debug("No se pudo determinar la direccion de origen: " + e.getMessage()
                    + " | source=ClientAuthController.direccionOrigen()");
        }
        return "desconocida";
    }

    /** 429 with Retry-After, so a client backs off instead of hammering. */
    private Response demasiadosIntentos(long segundos) {
        return Response.status(429)
                .header("Retry-After", String.valueOf(segundos))
                .entity(ApiResponse.error("TOO_MANY_ATTEMPTS",
                        "Demasiados intentos. Intentelo de nuevo en "
                                + (segundos / 60) + " minuto(s)."))
                .build();
    }
}
