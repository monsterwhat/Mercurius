package Controllers.Api.Marketplace;

import Models.Clients;
import Models.DTO.AuthResponse;
import Models.DTO.LoginRequest;
import Models.DTO.RegisterRequest;
import Models.DTO.ProfileDTO;
import Services.ClientAuthService;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;

import org.jboss.logging.Logger;

@Path("/api/marketplace/auth")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AuthController {

    private static final Logger LOG = Logger.getLogger(AuthController.class);

    @Inject
    @Nonnull
    ClientAuthService clientAuthService;

    @Context
    SecurityContext securityContext;

    @POST
    @Path("/register")
    @Nonnull
    public Response register(@Nonnull RegisterRequest request) {
        try {
            AuthResponse response = clientAuthService.register(request);
            return Response.status(Response.Status.CREATED).entity(response).build();
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("{\"error\":\"" + escapeJson(e.getMessage()) + "\"}")
                    .build();
        } catch (RuntimeException e) {
            LOG.error("Registration error", e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity("{\"error\":\"Error al registrar. Intente nuevamente.\"}")
                    .build();
        }
    }

    @POST
    @Path("/login")
    @Nonnull
    public Response login(@Nonnull LoginRequest request) {
        try {
            AuthResponse response = clientAuthService.login(request);
            return Response.ok(response).build();
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.UNAUTHORIZED)
                    .entity("{\"error\":\"" + escapeJson(e.getMessage()) + "\"}")
                    .build();
        } catch (RuntimeException e) {
            LOG.error("Login error", e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity("{\"error\":\"Error al iniciar sesión. Intente nuevamente.\"}")
                    .build();
        }
    }

    @POST
    @Path("/refresh")
    @Nonnull
    public Response refresh(@Nonnull RefreshTokenRequest request) {
        try {
            if (request.getRefreshToken() == null || request.getRefreshToken().isBlank()) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity("{\"error\":\"Token de actualización requerido\"}")
                        .build();
            }
            AuthResponse response = clientAuthService.refreshAccessToken(request.getRefreshToken());
            return Response.ok(response).build();
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.UNAUTHORIZED)
                    .entity("{\"error\":\"" + escapeJson(e.getMessage()) + "\"}")
                    .build();
        } catch (RuntimeException e) {
            LOG.error("Token refresh error", e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity("{\"error\":\"Error al actualizar sesión. Intente nuevamente.\"}")
                    .build();
        }
    }

    @GET
    @Path("/me")
    @Nonnull
    public Response getCurrentClient() {
        try {
            if (securityContext == null || securityContext.getUserPrincipal() == null) {
                return Response.status(Response.Status.UNAUTHORIZED)
                        .entity("{\"error\":\"No autenticado\"}")
                        .build();
            }
            int clientCode = Integer.parseInt(securityContext.getUserPrincipal().getName());
            Clients client = clientAuthService.findByCode(clientCode);
            if (client == null) {
                return Response.status(Response.Status.NOT_FOUND)
                        .entity("{\"error\":\"Cliente no encontrado\"}")
                        .build();
            }

            ProfileDTO profile = new ProfileDTO();
            profile.setCode(client.getCode());
            profile.setName(client.getName());
            profile.setEmail(client.getEmail());
            profile.setPhoneNumber(client.getPhoneNumber());
            profile.setAddress(client.getAddress());
            profile.setIdType(client.getIdType());
            profile.setIdNumber(client.getIdNumber());
            profile.setBirthDate(client.getBirthDate());
            profile.setPuntosAcumulados(client.getPuntosAcumulados());
            profile.setStatusPuntos(client.getStatusPuntos());

            return Response.ok(profile).build();
        } catch (RuntimeException e) {
            LOG.error("Error getting profile", e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity("{\"error\":\"Error al obtener perfil\"}")
                    .build();
        }
    }

    @Nonnull
    private static String escapeJson(@Nullable String value) {
        if (value == null) return "";
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    public static class RefreshTokenRequest {
        @Nullable private String refreshToken;
        @Nullable public String getRefreshToken() { return refreshToken; }
        public void setRefreshToken(@Nullable String refreshToken) { this.refreshToken = refreshToken; }
    }
}
