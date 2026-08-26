package Controllers.Api.App;

import Models.DTO.ApiResponse;
import Models.DTO.PagedResponse;
import Models.DTO.UsersDTO;
import Models.Users;
import Services.AlertasService;
import Services.LoginService;
import Utils.DiffUtils;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * System-user administration endpoints for the NEW Qute/HTMX app surface
 * (/app world).
 *
 * <p>Mirrors the legacy JSF {@code UsersController} behaviors as REST:
 * paginated listing, creation with duplicate-username guard and BCrypt
 * hashing ({@code createUser()} parity â€” {@link LoginService#create} hashes
 * the password BEFORE persist, so the raw password is handed to it untouched),
 * profile updates ({@code updateUser()} parity) and SOFT deletion
 * ({@code LoginService.softDelete} â€” users are never hard-deleted in this
 * system; {@code findByUsername} filters {@code status = true}). Password
 * changes go exclusively through {@code /{id}/password}, whose guard chain is
 * a verbatim port of {@code SessionController.changePassword()}.</p>
 *
 * <p>Role model mirrors {@code SessionController.isUsuarios()}: the whole
 * resource requires {@code usuario} OR {@code admin}; creation is additionally
 * restricted to {@code admin}. The {@code @RolesAllowed} gates are dormant
 * until the form-cookie auth block is enabled in application.properties (see
 * {@link AppAuthResource}).</p>
 *
 * <p>All responses follow the {@link ApiResponse}/{@link PagedResponse}
 * envelope conventions. {@link UsersDTO} intentionally omits the password
 * hash â€” never expose credentials through this resource. Write methods are
 * {@code @Transactional} because {@code LoginService.create/softDelete/
 * updatePassword} carry no transaction annotation of their own.</p>
 */
@Path("/api/app/users")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed({"admin", "usuario"})
@Tag(name = "App - Usuarios")
public class UsersResource {

    private static final Logger LOG = Logger.getLogger(UsersResource.class.getName());

    @Inject
    @Nonnull
    LoginService loginService;

    @Inject
    @Nonnull
    AlertasService alertas;

    @GET
    @Operation(summary = "List system users with pagination")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Success"),
        @APIResponse(responseCode = "401", description = "Not authenticated"),
        @APIResponse(responseCode = "403", description = "Missing admin/usuario role"),
        @APIResponse(responseCode = "500", description = "Internal server error")
    })
    public Response list(
            @QueryParam("page") @DefaultValue("0") @Parameter(description = "Page number (0-based)") int page,
            @QueryParam("size") @DefaultValue("20") @Parameter(description = "Page size (max 100)") int size) {

        // Clamp size to max 100 (SuppliersController convention)
        size = Math.min(Math.max(size, 1), 100);
        page = Math.max(page, 0);

        try {
            long total = loginService.count();
            List<UsersDTO> data = loginService.listPage(page * size, size).stream()
                    .map(user -> toDTO(user))
                    .toList();
            return Response.ok(new PagedResponse<>(data, total, page, size)).build();
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Error listing users", e);
            return Response.serverError()
                    .entity(ApiResponse.error("INTERNAL_ERROR", "Error listando los usuarios"))
                    .build();
        }
    }

    @POST
    @RolesAllowed("admin")
    @Transactional
    @Operation(summary = "Create a system user (admin only; password is BCrypt-hashed before persist)")
    @APIResponses({
        @APIResponse(responseCode = "201", description = "Created"),
        @APIResponse(responseCode = "400", description = "Validation error"),
        @APIResponse(responseCode = "401", description = "Not authenticated"),
        @APIResponse(responseCode = "403", description = "Missing admin role"),
        @APIResponse(responseCode = "409", description = "Duplicate username"),
        @APIResponse(responseCode = "500", description = "Internal server error")
    })
    public Response create(@Nullable CreateUserRequest request) {
        try {
            if (request == null || request.username == null || request.username.isBlank()
                    || request.password == null || request.password.isBlank()) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(ApiResponse.error("VALIDATION_ERROR",
                                "El usuario y la contraseÃ±a no pueden estar vacÃ­os."))
                        .build();
            }

            // Parity with UsersController.createUser(): duplicate usernames are
            // rejected with the legacy warning text.
            if (loginService.usernameExists(request.username)) {
                return Response.status(Response.Status.CONFLICT)
                        .entity(ApiResponse.error("USERNAME_TAKEN",
                                "Ya existe un usuario con ese nombre"))
                        .build();
            }

            Users user = new Users();
            user.setUsername(request.username.trim());
            // Raw password on purpose: LoginService.create() hashes it with
            // BCrypt (cost 12) BEFORE persist â€” identical to the legacy flow.
            user.setPassword(request.password);
            user.setEmail(request.email);
            user.setGroupName(request.groupName);
            user.setStatus(true); // parity: createUser() always enables new users
            loginService.create(user);

            alertas.registrarAlerta("Usuario Creado",
                    "Se creo el usuario: " + user.getUsername(),
                    null, 0, "UsersResource.create()",
                    null, user.getUsername());

            return Response.status(Response.Status.CREATED)
                    .entity(ApiResponse.ok(toDTO(user)))
                    .build();
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Error creating user", e);
            return Response.serverError()
                    .entity(ApiResponse.error("INTERNAL_ERROR", "Error creando el usuario"))
                    .build();
        }
    }

    @PUT
    @Path("/{id}")
    @Transactional
    @Operation(summary = "Update a user's username/email/status")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Updated"),
        @APIResponse(responseCode = "400", description = "Validation error"),
        @APIResponse(responseCode = "401", description = "Not authenticated"),
        @APIResponse(responseCode = "403", description = "Missing admin/usuario role"),
        @APIResponse(responseCode = "404", description = "Not found"),
        @APIResponse(responseCode = "500", description = "Internal server error")
    })
    public Response update(
            @PathParam("id") @Parameter(description = "User ID") Long id,
            @Nullable UpdateUserRequest request) {
        try {
            if (request == null) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(ApiResponse.error("VALIDATION_ERROR",
                                "El cuerpo de la peticiÃ³n es requerido."))
                        .build();
            }
            if (request.username != null && request.username.isBlank()) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(ApiResponse.error("VALIDATION_ERROR",
                                "El usuario no puede estar vacÃ­o."))
                        .build();
            }

            Users user = loginService.find(id);
            if (user == null) {
                return notFound(id);
            }

            String antes = DiffUtils.snapshotEntity(user);
            if (request.username != null) {
                user.setUsername(request.username.trim());
            }
            if (request.email != null) {
                user.setEmail(request.email);
            }
            if (request.status != null) {
                user.setStatus(request.status);
            }
            loginService.update(user);

            // Audit parity with UsersController.updateUser()
            alertas.registrarAlerta("Usuario Actualizado",
                    "Se actualizo el usuario: " + user.getUsername(),
                    null, 0, "UsersResource.update()",
                    antes, DiffUtils.snapshotEntity(user));

            return Response.ok(ApiResponse.ok(toDTO(user))).build();
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Error updating user " + id, e);
            return Response.serverError()
                    .entity(ApiResponse.error("INTERNAL_ERROR", "Error actualizando el usuario"))
                    .build();
        }
    }

    @DELETE
    @Path("/{id}")
    @Transactional
    @Operation(summary = "Archive (soft-disable) a user")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Archived (status set to false)"),
        @APIResponse(responseCode = "401", description = "Not authenticated"),
        @APIResponse(responseCode = "403", description = "Missing admin/usuario role"),
        @APIResponse(responseCode = "404", description = "Not found"),
        @APIResponse(responseCode = "500", description = "Internal server error")
    })
    public Response delete(@PathParam("id") @Parameter(description = "User ID") Long id) {
        try {
            Users user = loginService.find(id);
            if (user == null) {
                return notFound(id);
            }

            // Parity: users are never hard-deleted (Clients.usuario and other
            // rows reference them); LoginService.softDelete flips status=false,
            // which findByUsername already filters out.
            String antes = DiffUtils.snapshotEntity(user);
            loginService.softDelete(user);

            // Audit parity with UsersController.toggleUser()
            alertas.registrarAlerta("Estado de Usuario Cambiado",
                    "Se cambio el estado del usuario: " + user.getUsername(),
                    null, 0, "UsersResource.delete()",
                    antes, DiffUtils.snapshotEntity(user));

            Users updated = loginService.find(id);
            return Response.ok(ApiResponse.ok(toDTO(updated != null ? updated : user))).build();
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Error deleting user " + id, e);
            return Response.serverError()
                    .entity(ApiResponse.error("INTERNAL_ERROR", "Error eliminando el usuario"))
                    .build();
        }
    }

    @PUT
    @Path("/{id}/password")
    @Transactional
    @Operation(summary = "Change a user's password after verifying the current one")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Password changed"),
        @APIResponse(responseCode = "400", description = "Guard failure (blank/mismatched/incorrect passwords)"),
        @APIResponse(responseCode = "401", description = "Not authenticated"),
        @APIResponse(responseCode = "403", description = "Missing admin/usuario role"),
        @APIResponse(responseCode = "404", description = "Not found"),
        @APIResponse(responseCode = "500", description = "Internal server error")
    })
    public Response changePassword(
            @PathParam("id") @Parameter(description = "User ID") Long id,
            @Nullable ChangePasswordRequest request) {
        try {
            // Guard chain ported VERBATIM from SessionController.changePassword():
            // blank-new â†’ confirm-match â†’ current-blank â†’ verify-current â†’ update.
            if (request == null || request.newPassword == null || request.newPassword.isBlank()) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(ApiResponse.error("VALIDATION_ERROR",
                                "La nueva contrasena no puede estar vacia"))
                        .build();
            }
            if (request.confirmPassword != null && !request.newPassword.equals(request.confirmPassword)) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(ApiResponse.error("VALIDATION_ERROR",
                                "Las contrasenas no son iguales."))
                        .build();
            }
            if (request.currentPassword == null || request.currentPassword.isBlank()) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(ApiResponse.error("VALIDATION_ERROR",
                                "La contrasena actual no puede estar vacia."))
                        .build();
            }

            Users user = loginService.find(id);
            if (user == null) {
                return notFound(id);
            }
            if (!loginService.verifyPassword(request.currentPassword, user.getPassword())) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(ApiResponse.error("VALIDATION_ERROR",
                                "La contrasena actual es incorrecta."))
                        .build();
            }

            loginService.updatePassword(user, request.newPassword);

            alertas.registrarAlerta("ContraseÃ±a Cambiada",
                    "Se cambiÃ³ la contraseÃ±a",
                    null, 0, "UsersResource.changePassword()",
                    null, null);

            return Response.ok(ApiResponse.ok(toDTO(user))).build();
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Error changing password for user " + id, e);
            return Response.serverError()
                    .entity(ApiResponse.error("INTERNAL_ERROR", "Error cambiando la contraseÃ±a"))
                    .build();
        }
    }

    // â”€â”€ Helpers â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    private static Response notFound(Long id) {
        return Response.status(Response.Status.NOT_FOUND)
                .entity(ApiResponse.error("NOT_FOUND", "No se encontrÃ³ el usuario: " + id))
                .build();
    }

    /**
     * Read-side mapping ONLY â€” {@link UsersDTO} excludes the password hash by design.
     */
    private static UsersDTO toDTO(Users user) {
        return new UsersDTO(user.getId(), user.getUsername(), user.getEmail(),
                user.getGroupName(), user.getStatus());
    }

    /**
     * Creation payload. Kept out of {@link UsersDTO} because that DTO must
     * never carry credential fields (see its SECURITY note).
     */
    public static class CreateUserRequest {
        public String username;
        public String password; // raw; hashed by LoginService.create() before persist
        @Nullable
        public String email;
        @Nullable
        public String groupName;
    }

    /** Profile-update payload (password changes are NOT accepted here). */
    public static class UpdateUserRequest {
        @Nullable
        public String username;
        @Nullable
        public String email;
        @Nullable
        public Boolean status;
    }

    /** Password-change payload mirroring SessionController's three fields. */
    public static class ChangePasswordRequest {
        @Nullable
        public String currentPassword;
        @Nullable
        public String newPassword;
        @Nullable
        public String confirmPassword; // optional; equality enforced when present
    }
}
