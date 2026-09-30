package Controllers.Api.Marketplace;

import Models.Clientes;
import Models.DTO.ApiResponse;
import Models.DTO.AuthResponse;
import Models.DTO.LoginRequest;
import Models.DTO.RegisterRequest;
import Models.DTO.ProfileDTO;
import Services.ClientAuthService;
import io.vertx.ext.web.RoutingContext;
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

    /** Bounds password guessing against {@link #login(LoginRequest)}. */
    @Nonnull
    @Inject
    Utils.IntentosDeCredencial intentosDeCredencial;

    /** Source address for throttling; see {@link #direccionOrigen()}. */
    @Inject
    @Nullable
    RoutingContext routing;

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
     *
     * <p>The 401 goes out in the same {@link ApiResponse} envelope as the 429 of
     * {@link #demasiadosIntentos(long)} — same class, same {@code error.code}
     * vocabulary — so a marketplace client reads a failed login and a throttled
     * login through one accessor instead of two. The two controllers under
     * {@code /api/marketplace} and {@code /api/v1/mercatus} are byte-for-byte
     * interchangeable on this status.</p>
     */
    @POST
    @Path("/login")
    @Nonnull
    public Response login(@Nonnull LoginRequest request) {
        String direccion = direccionOrigen();

        Long bloqueo = intentosDeCredencial.restanteBloqueo(request.getEmail(), direccion);
        if (bloqueo != null) {
            return demasiadosIntentos(bloqueo);
        }

        try {
            AuthResponse response = clientAuthService.login(request, direccion);
            return Response.ok(response).build();
        } catch (IllegalArgumentException e) {
            LOG.debug("Login rechazado: " + e.getMessage()
                    + " | source=AuthController.login()");
            return credencialesInvalidas(e);
        } catch (RuntimeException e) {
            LOG.error("Login error", e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity("{\"error\":\"Error al iniciar sesión. Intente nuevamente.\"}")
                    .build();
        }
    }

    /**
     * 401 con envelope {@link ApiResponse}, identico al que ya emitia el
     * controlador de mercatus, para que los dos endpoints de login no se
     * distinguan ni por codigo ni por forma.
     *
     * <p>El codigo es {@code INVALID_CREDENTIALS} —el mismo que ya usaba
     * {@code Controllers.Api.Mercatus.ClientAuthController} y
     * {@code AppAuthResource.invalidCredentials()}, y el que el cliente ya
     * recibe ahi— de modo que unificar la FORMA no obliga a ningun consumidor a
     * cambiar la clave que ya tenia. El mensaje lo aporta
     * {@link ClientAuthService#MENSAJE_CREDENCIALES_INVALIDAS}, identico en las
     * cuatro ramas de rechazo.</p>
     */
    @Nonnull
    private Response credencialesInvalidas(@Nonnull IllegalArgumentException causa) {
        return Response.status(Response.Status.UNAUTHORIZED)
                .entity(ApiResponse.error("INVALID_CREDENTIALS", causa.getMessage()))
                .build();
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
                    + " | source=AuthController.direccionOrigen()");
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

    /**
     * Renueva el token de acceso.
     *
     * <p>El 401 usa el mismo envelope {@link ApiResponse} que el de login, pero
     * con codigo {@code INVALID_TOKEN}: no son credenciales, es un token de
     * actualizacion rechazado, y reutilizar {@code INVALID_CREDENTIALS} obligaria
     * al cliente a distinguir por el mensaje lo que el codigo ya dice.</p>
     */
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
                    .entity(ApiResponse.error("INVALID_TOKEN", e.getMessage()))
                    .build();
        } catch (RuntimeException e) {
            LOG.error("Token refresh error", e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity("{\"error\":\"Error al actualizar sesión. Intente nuevamente.\"}")
                    .build();
        }
    }

    /**
     * Perfil del cliente autenticado.
     *
     * <p>Sin identidad ({@code securityContext} vacio o sin principal) se
     * responde con el envelope {@link ApiResponse} y codigo
     * {@code UNAUTHENTICATED}, el mismo que ya usa
     * {@code AppAuthResource.me()} para la autenticacion por formulario.</p>
     */
    @GET
    @Path("/me")
    @Nonnull
    public Response getCurrentClient() {
        try {
            if (securityContext == null || securityContext.getUserPrincipal() == null) {
                return Response.status(Response.Status.UNAUTHORIZED)
                        .entity(ApiResponse.error("UNAUTHENTICATED", "No autenticado"))
                        .build();
            }
            int clientCode = Integer.parseInt(securityContext.getUserPrincipal().getName());
            Clientes client = clientAuthService.findByCode(clientCode);
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
