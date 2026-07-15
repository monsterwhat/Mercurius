package Controllers.Api.Mercatus;

import Models.DTO.ApiResponse;
import Models.DTO.AuthResponse;
import Models.DTO.LoginRequest;
import Models.DTO.RegisterRequest;
import Services.ClientAuthService;
import jakarta.annotation.Nonnull;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.logging.Logger;
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

    private static final Logger LOG = Logger.getLogger(ClientAuthController.class.getName());

    @Inject
    @Nonnull
    ClientAuthService clientAuthService;

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
            LOG.warning("Registration error: " + e.getMessage());
            return Response.serverError()
                    .entity(ApiResponse.error("INTERNAL_ERROR", "Registration failed"))
                    .build();
        }
    }

    @POST
    @Path("/auth/login")
    @Operation(summary = "Login with email and password to get JWT token")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Login successful"),
        @APIResponse(responseCode = "401", description = "Invalid credentials"),
        @APIResponse(responseCode = "500", description = "Internal server error")
    })
    public Response login(@Nonnull LoginRequest request) {
        try {
            AuthResponse authResponse = clientAuthService.login(request);
            return Response.ok(authResponse).build();
        } catch (IllegalArgumentException e) {
            LOG.info("Login failed: " + e.getMessage());
            return Response.status(Response.Status.UNAUTHORIZED)
                    .entity(ApiResponse.error("INVALID_CREDENTIALS", e.getMessage()))
                    .build();
        } catch (Exception e) {
            LOG.warning("Login error: " + e.getMessage());
            return Response.serverError()
                    .entity(ApiResponse.error("INTERNAL_ERROR", "Login failed"))
                    .build();
        }
    }
}
