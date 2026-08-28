package Controllers.Api.App;

import Models.DTO.ApiResponse;
import Models.DTO.UsersDTO;
import Models.Users;
import Services.LoginService;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import io.quarkus.security.identity.SecurityIdentity;
import io.vertx.ext.web.RoutingContext;
import jakarta.annotation.Nonnull;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * JSON/HTMX twin of {@link PerfilPagesResource}: the logged-in user's own
 * profile mutations ({@code /api/app/perfil}). Guard chains are ported
 * VERBATIM from SessionController.changeName/changeEmail/changePassword.
 *
 * HTMX callers (the perfil page forms) get an HX-Redirect to /app/perfil on
 * success and a 400 OOB toast fragment on guard failure; JSON callers keep
 * the standard ApiResponse envelope.
 */
@Path("/api/app/perfil")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed({"admin", "inventario", "facturacion", "tributacion", "usuario", "registro"})
public class ProfileResource {
    private static final Logger LOG = Logger.getLogger(ProfileResource.class.getName());

    @Inject
    @Nonnull
    LoginService loginService;

    @Inject
    @Nonnull
    SecurityIdentity identity;

    @Inject
    @Nonnull
    RoutingContext routing;

    @Inject
    @Nonnull
    @Location("pages/perfil/toast")
    Template toast;

    @GET
    public Response getProfile() {
        Users user = currentUser();
        if (user == null) {
            return Response.status(Response.Status.UNAUTHORIZED)
                    .entity(ApiResponse.error("UNAUTHORIZED", "No autenticado"))
                    .build();
        }
        return Response.ok(ApiResponse.ok(toDTO(user))).build();
    }

    @PUT
    @Path("/nombre")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public Response changeNombre(@FormParam("nuevoNombre") String nuevoNombre) {
        Users user = currentUser();
        if (user == null) {
            return unauthorized();
        }
        if (nuevoNombre == null || nuevoNombre.isBlank()) {
            return badRequest("VALIDATION_ERROR", "El nuevo nombre no puede estar vacio.");
        }
        String nuevo = nuevoNombre.trim();
        if (user.getUsername().equals(nuevo)) {
            return badRequest("VALIDATION_ERROR", "El nuevo nombre no puede ser igual");
        }
        if (loginService.usernameExists(nuevo)) {
            return badRequest("VALIDATION_ERROR", "El nombre de usuario ya esta en uso.");
        }
        loginService.updateUsername(user, nuevo);
        LOG.info("Se actualizo el nombre de usuario a: " + nuevo + " | source=" + "ProfileResource.changeNombre()");
        return okOrRedirect(user);
    }

    @PUT
    @Path("/email")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public Response changeEmail(@FormParam("nuevoEmail") String nuevoEmail) {
        Users user = currentUser();
        if (user == null) {
            return unauthorized();
        }
        if (nuevoEmail == null || nuevoEmail.isBlank()) {
            return badRequest("VALIDATION_ERROR", "El nuevo correo no puede estar vacio.");
        }
        String nuevo = nuevoEmail.trim();
        if (nuevo.equals(user.getEmail())) {
            return badRequest("VALIDATION_ERROR", "El nuevo correo no puede ser igual");
        }
        loginService.updateEmail(user, nuevo);
        LOG.info("Se actualizo el correo electronico a: " + nuevo + " | source=" + "ProfileResource.changeEmail()");
        return okOrRedirect(user);
    }

    @PUT
    @Path("/password")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public Response changePassword(@FormParam("currentPassword") String currentPassword,
                                   @FormParam("newPassword") String newPassword,
                                   @FormParam("confirmPassword") String confirmPassword) {
        Users user = currentUser();
        if (user == null) {
            return unauthorized();
        }
        if (newPassword == null || newPassword.isBlank()) {
            return badRequest("VALIDATION_ERROR", "La nueva contrasena no puede estar vacia");
        }
        if (confirmPassword != null && !newPassword.equals(confirmPassword)) {
            return badRequest("VALIDATION_ERROR", "Las contrasenas no son iguales.");
        }
        if (currentPassword == null || currentPassword.isBlank()) {
            return badRequest("VALIDATION_ERROR", "La contrasena actual no puede estar vacia.");
        }
        if (!loginService.verifyPassword(currentPassword, user.getPassword())) {
            return badRequest("VALIDATION_ERROR", "La contrasena actual es incorrecta.");
        }
        loginService.updatePassword(user, newPassword);
        LOG.info("Se cambio la contrasena" + " | source=" + "ProfileResource.changePassword()");
        return okOrRedirect(user);
    }

    // ── Helpers ─────────────────────────────────────────────────────

    private Users currentUser() {
        if (identity == null || identity.isAnonymous()) {
            return null;
        }
        String principal = identity.getPrincipal() != null ? identity.getPrincipal().getName() : null;
        if (principal == null) {
            return null;
        }
        return loginService.findByUsername(principal);
    }

    /** True when the call comes from HTMX (layout hx-headers carries it). */
    private boolean isHxRequest() {
        String header = routing.request().getHeader("HX-Request");
        return header != null && !"false".equalsIgnoreCase(header);
    }

    private Response unauthorized() {
        return Response.status(Response.Status.UNAUTHORIZED)
                .entity(ApiResponse.error("UNAUTHORIZED", "No autenticado"))
                .build();
    }

    private Response badRequest(@Nonnull String code, @Nonnull String message) {
        if (isHxRequest()) {
            TemplateInstance instance = toast.instance()
                    .data("severity", "warn")
                    .data("message", message);
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(instance.render())
                    .type(MediaType.TEXT_HTML_TYPE.withCharset("UTF-8"))
                    .build();
        }
        return Response.status(Response.Status.BAD_REQUEST)
                .entity(ApiResponse.error(code, message))
                .build();
    }

    private Response okOrRedirect(@Nonnull Users user) {
        if (isHxRequest()) {
            return Response.status(Response.Status.OK)
                    .header("HX-Redirect", "/app/perfil")
                    .build();
        }
        return Response.ok(ApiResponse.ok(toDTO(user))).build();
    }

    private static UsersDTO toDTO(Users user) {
        return new UsersDTO(user.getId(), user.getUsername(), user.getEmail(),
                user.getGroupName(), user.getStatus());
    }
}