package Controllers.Api.App;

import Models.DTO.ApiResponse;
import Models.DTO.PagedResponse;
import Models.DTO.UsersDTO;
import Models.Users;
import Services.LoginService;
import Utils.DiffUtils;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import io.vertx.ext.web.RoutingContext;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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

    @Nonnull
    LoginService loginService;

    
    /** Request context (quarkus-rest injectable) — source of HX-Request. */
    @Nonnull
    RoutingContext routing;

    // Templates (rendered to String: no quarkus-rest-qute MessageBodyWriter
    // on this stack — same approach as CategoriaResource, T18).
    @Nonnull
    @Location("pages/usuarios/index.html")
    Template pageIndex;

    @Nonnull
    @Location("pages/usuarios/tabla.html")
    Template tablaPage;

    @Nonnull
    @Location("pages/usuarios/form.html")
    Template formUsuario;

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

                        LOG.info("Se creo el usuario: " + user.getUsername() + " | source=" + "UsersResource.create()" + " | antes=" + String.valueOf(null) + " | despues=" + String.valueOf(user.getUsername()));

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
                        LOG.info("Se actualizo el usuario: " + user.getUsername() + " | source=" + "UsersResource.update()" + " | antes=" + String.valueOf(antes) + " | despues=" + String.valueOf(DiffUtils.snapshotEntity(user)));

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
                        LOG.info("Se cambio el estado del usuario: " + user.getUsername() + " | source=" + "UsersResource.delete()" + " | antes=" + String.valueOf(antes) + " | despues=" + String.valueOf(DiffUtils.snapshotEntity(user)));

            // HTMX callers get the refreshed table fragment + OOB toast
            // (ui-kit §7 update="table region"); JSON callers keep the exact
            // envelope below.
            if (isHxRequest()) {
                return htmlOk(tableInstance(1, 20, null, "asc", null, "warn",
                        "Se cambio el estado del usuario: " + user.getUsername()));
            }

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

                        LOG.info("Se cambiÃ³ la contraseÃ±a" + " | source=" + "UsersResource.changePassword()" + " | antes=" + String.valueOf(null) + " | despues=" + String.valueOf(null));

            return Response.ok(ApiResponse.ok(toDTO(user))).build();
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Error changing password for user " + id, e);
            return Response.serverError()
                    .entity(ApiResponse.error("INTERNAL_ERROR", "Error cambiando la contraseÃ±a"))
                    .build();
        }
    }

    // â”€â”€ Helpers â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    // ── W4B view-half: dual-mode table endpoint + dialog fragments ─────────

    /**
     * GET /table?page&size&sort&dir&q — with the {@code HX-Request} header
     * returns ONLY the data-table include (fragment swap into the page's
     * table container); without it renders the FULL usuarios page. This
     * mirrors the SERVER-SIDE CONTRACT comment of
     * {@code templates/_kit/data-table.html} exactly: the same endpoint
     * renders page and fragments, all paging/sorting state lives in the URL,
     * and {@code page} is 1-based here (the JSON list endpoint above keeps
     * its own 0-based contract untouched). {@code q} ports the legacy
     * globalFilterFunction ("Búsqueda general de todos los campos") over
     * username/email/groupName — the JSON list endpoint has no filter and
     * stays untouched.
     */
    @GET
    @Path("/table")
    @Produces(MediaType.TEXT_HTML)
    @Operation(summary = "Data-table fragment (HX-Request) or full usuarios page")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "HTML fragment or full page"),
        @APIResponse(responseCode = "500", description = "Template rendering failure")
    })
    public Response table(
            @QueryParam("page") @DefaultValue("1") int page,
            @QueryParam("size") @DefaultValue("20") int size,
            @QueryParam("sort") @Nullable String sort,
            @QueryParam("dir") @DefaultValue("asc") String dir,
            @QueryParam("q") @Nullable String q) {
        try {
            if (isHxRequest()) {
                return htmlOk(tableInstance(page, size, sort, dir, q, null, null));
            }
            return htmlOk(renderFullPage());
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Error renderizando la página de usuarios", e);
            return Response.serverError()
                    .entity(ApiResponse.error("INTERNAL_ERROR", "Error renderizando la página"))
                    .build();
        }
    }

    /** Empty user creation form (modal body) — admin-only, mirroring POST. */
    @GET
    @Path("/formularios/nuevo")
    @RolesAllowed("admin")
    @Produces(MediaType.TEXT_HTML)
    @Operation(summary = "New-user form fragment (modal body, admin only)")
    public Response formNuevo() {
        return htmlOk(formInstance("crear", null, null, null, null, null, null));
    }

    /** Prefilled user edit form (modal body). */
    @GET
    @Path("/formularios/{id}")
    @Produces(MediaType.TEXT_HTML)
    @Operation(summary = "Edit-user form fragment (modal body)")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Form HTML"),
        @APIResponse(responseCode = "404", description = "Unknown id")
    })
    public Response formEditar(@PathParam("id") Long id) {
        Users user = loginService.find(id);
        if (user == null) {
            return notFound(id);
        }
        return htmlOk(formInstance("editar", user, null, null, null, null, null));
    }

    /**
     * Form-urlencoded twin of {@link #create} for the HTMX dialog (JAX-RS
     * selects by Content-Type; the JSON contract above is untouched). The
     * permisos checkboxes are joined into UsersController's
     * Arrays.toString storage format ("[a, b]"), which UserRoleMapper parses.
     */
    @POST
    @RolesAllowed("admin")
    @Transactional
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Operation(summary = "Create a user from an HTMX form", hidden = true)
    public Response createForm(
            @FormParam("username") @Nullable String username,
            @FormParam("password") @Nullable String password,
            @FormParam("email") @Nullable String email,
            @FormParam("groupName") @Nullable List<String> groupName) {
        String nombre = trimToEmpty(username);
        if (nombre.isEmpty()) {
            return redisplayForm("crear", null,
                    "El nombre de usuario no puede estar vacío", null, null, null,
                    "error", "El nombre de usuario no puede estar vacío");
        }
        if (password == null || password.isBlank()) {
            return redisplayForm("crear", null, null,
                    "La contraseña no puede estar vacía", null, null,
                    "error", "La contraseña no puede estar vacía");
        }
        List<String> permisos = permisosLimpios(groupName);
        if (permisos.isEmpty()) {
            return redisplayForm("crear", null, null, null,
                    "Los permisos no pueden estar vacíos", "Los permisos no pueden estar vacíos",
                    "error", "Los permisos no pueden estar vacíos");
        }

        CreateUserRequest request = new CreateUserRequest();
        request.username = nombre;
        request.password = password;
        request.email = emptyToNull(email);
        request.groupName = joinGroupNames(permisos);
        Response result = create(request);
        return handleFormMutationResult(result, "crear", null, null, null,
                "/api/app/users/table");
    }

    /**
     * Form-urlencoded twin of {@link #update} for the HTMX edit dialog.
     * Only username/email travel (the REST contract has no groupName update —
     * documented parity gap on pages/usuarios/form.html).
     */
    @PUT
    @Path("/{id}")
    @Transactional
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Operation(summary = "Update a user from an HTMX form", hidden = true)
    public Response updateForm(
            @PathParam("id") Long id,
            @FormParam("username") @Nullable String username,
            @FormParam("email") @Nullable String email) {
        String nombre = trimToEmpty(username);
        if (nombre.isEmpty()) {
            return redisplayForm("editar", loginService.find(id),
                    "El usuario no puede estar vacío.", null, null, null,
                    "error", "El usuario no puede estar vacío.");
        }

        UpdateUserRequest request = new UpdateUserRequest();
        request.username = nombre;
        request.email = emptyToNull(email);
        Response result = update(id, request);
        return handleFormMutationResult(result, "editar", loginService.find(id), null, null,
                "/api/app/users/table");
    }

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

    // ── W4B template-model helpers (CategoriaResource/T18 conventions) ──────

    /** True when the call comes from HTMX (layout hx-headers carries it). */
    private boolean isHxRequest() {
        String header = routing.request().getHeader("HX-Request");
        return header != null && !"false".equalsIgnoreCase(header);
    }

    private static Response htmlOk(@Nonnull TemplateInstance template) {
        return Response.ok(template.render())
                .type(MediaType.TEXT_HTML_TYPE.withCharset("UTF-8")).build();
    }

    private static Response hxRedirect(@Nonnull String url) {
        return Response.status(Response.Status.OK)
                .header("HX-Redirect", url)
                .build();
    }

    private static String trimToEmpty(@Nullable String raw) {
        return raw == null ? "" : raw.trim();
    }

    @Nullable
    private static String emptyToNull(@Nullable String raw) {
        return raw == null || raw.isBlank() ? null : raw;
    }

    private static List<String> permisosLimpios(@Nullable List<String> groupName) {
        List<String> out = new ArrayList<>();
        if (groupName != null) {
            for (String token : groupName) {
                if (token != null && !token.isBlank()) {
                    out.add(token.trim());
                }
            }
        }
        return out;
    }

    /**
     * UsersController stored {@code Arrays.toString(selectedPuestos)} into
     * groupName ("[admin, usuario]"); UserRoleMapper parses that shape back,
     * so the form twin must reproduce it verbatim.
     */
    @Nonnull
    private static String joinGroupNames(@Nonnull List<String> permisos) {
        return "[" + String.join(", ", permisos) + "]";
    }

    private Response handleFormMutationResult(@Nonnull Response result,
                                              @Nonnull String modo,
                                              @Nullable Users usuario,
                                              @Nullable String errorUsuario,
                                              @Nullable String errorPassword,
                                              @Nonnull String redirectUrl) {
        if (!isHxRequest()) {
            return result;
        }
        int status = result.getStatus();
        if (status == Response.Status.CREATED.getStatusCode()
                || status == Response.Status.OK.getStatusCode()) {
            return hxRedirect(redirectUrl);
        }
        String mensaje = "No se pudo guardar el usuario";
        if (result.getEntity() instanceof ApiResponse<?> api && api.getError() != null) {
            mensaje = api.getError().getMessage();
        }
        return redisplayForm(modo, usuario, errorUsuario, errorPassword, null, mensaje, "error", mensaje);
    }

    private Response redisplayForm(@Nonnull String modo, @Nullable Users usuario,
                                   @Nullable String errorUsuario, @Nullable String errorPassword,
                                   @Nullable String errorPermisos, @Nullable String errorGeneral,
                                   @Nullable String toastSeverity, @Nullable String toastMessage) {
        return Response.status(Response.Status.BAD_REQUEST)
                .type(MediaType.TEXT_HTML_TYPE.withCharset("UTF-8"))
                .entity(formInstance(modo, usuario, errorUsuario, errorPassword,
                        errorGeneral, toastSeverity, toastMessage)
                        .data("errorPermisos", errorPermisos).render())
                .build();
    }

    private TemplateInstance formInstance(@Nonnull String modo, @Nullable Users usuario,
                                          @Nullable String errorUsuario,
                                          @Nullable String errorPassword,
                                          @Nullable String errorGeneral,
                                          @Nullable String toastSeverity,
                                          @Nullable String toastMessage) {
        return formUsuario
                .data("modo", modo)
                .data("usuario", usuario)
                .data("errorUsuario", errorUsuario)
                .data("errorPassword", errorPassword)
                .data("errorPermisos", null)
                .data("errorGeneral", errorGeneral)
                .data("toastSeverity", toastSeverity)
                .data("toastMessage", toastMessage);
    }

    private TemplateInstance renderFullPage() {
        TableModel model = buildTableModel(1, 20, null, "asc", null);
        List<Users> todos = new ArrayList<>(loginService.listAll());
        long activos = todos.stream().filter(u -> u.getStatus() != null && u.getStatus()).count();
        return pageIndex
                .data("tablaUsuarios", model.asMap())
                .data("usuariosTotal", model.total())
                .data("usuariosActivosCount", activos)
                .data("usuariosInactivosCount", todos.size() - activos);
    }

    private TemplateInstance tableInstance(int page, int size, @Nullable String sort,
                                           @Nullable String dir, @Nullable String q,
                                           @Nullable String toastSeverity,
                                           @Nullable String toastMessage) {
        TableModel model = buildTableModel(page, size, sort, dir, q);
        return tablaPage
                .data("modelo", model.asMap())
                .data("q", model.q())
                .data("toastSeverity", toastSeverity)
                .data("toastMessage", toastMessage);
    }

    private TableModel buildTableModel(int page, int size, @Nullable String sort,
                                       @Nullable String dir, @Nullable String q) {
        List<Users> filas = new ArrayList<>(loginService.listAll());
        if (q != null && !q.isBlank()) {
            String needle = q.trim().toLowerCase(Locale.ROOT);
            filas.removeIf(user -> !matchesFilter(user, needle));
        }
        sortUsers(filas, sort, dir);

        long total = filas.size();
        Window w = windowOf(total, page, size);
        filas = filas.subList(w.from(), w.to());

        List<Map<String, Object>> columnas = new ArrayList<>();
        columnas.add(col("Identificador de perfil", "id"));
        columnas.add(col("Nombre de usuario", "username"));
        columnas.add(col("Email", "email"));
        columnas.add(col("Nombre del grupo", "groupName"));
        columnas.add(col("Activo", "status"));
        columnas.add(col("Acciones", null));

        Map<String, Object> filtros = new LinkedHashMap<>();
        if (q != null && !q.isBlank()) {
            filtros.put("q", q.trim());
        }

        return new TableModel("tabla-usuarios", "/api/app/users/table", columnas, filas,
                sort, "desc".equalsIgnoreCase(dir) ? "desc" : "asc",
                w.page(), w.size(), total, w.totalPages(), pageWindow(w.page(), w.totalPages()),
                filtros, q);
    }

    private static boolean matchesFilter(@Nonnull Users user, @Nonnull String needle) {
        return containsIgnoreCase(user.getUsername(), needle)
                || containsIgnoreCase(user.getEmail(), needle)
                || containsIgnoreCase(user.getGroupName(), needle);
    }

    private static boolean containsIgnoreCase(@Nullable String value, @Nonnull String needle) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(needle);
    }

    private static void sortUsers(@Nonnull List<Users> rows, @Nullable String sort,
                                  @Nullable String dir) {
        if (rows.isEmpty() || sort == null || sort.isBlank()) {
            return;
        }
        Comparator<Users> cmp = switch (sort) {
            case "id" -> Comparator.comparing(Users::getId,
                    Comparator.nullsLast(Comparator.naturalOrder()));
            case "username" -> Comparator.comparing(Users::getUsername,
                    Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
            case "email" -> Comparator.comparing(Users::getEmail,
                    Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
            case "groupName" -> Comparator.comparing(Users::getGroupName,
                    Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
            case "status" -> Comparator.comparing(Users::getStatus,
                    Comparator.nullsLast(Comparator.naturalOrder()));
            default -> null;
        };
        if (cmp != null) {
            rows.sort("desc".equalsIgnoreCase(dir) ? cmp.reversed() : cmp);
        }
    }

    private static Map<String, Object> col(@Nonnull String label, @Nullable String key) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("label", label);
        map.put("key", key);
        return map;
    }

    private static List<Integer> pageWindow(int page, int totalPages) {
        if (totalPages <= 1) {
            return List.of(1);
        }
        List<Integer> pages = new ArrayList<>();
        int from = Math.max(1, page - 2);
        int to = Math.min(totalPages, page + 2);
        for (int i = from; i <= to; i++) {
            pages.add(i);
        }
        return pages;
    }

    private record Window(int page, int size, int from, int to, int totalPages) {}

    private static Window windowOf(long total, int page, int size) {
        int s = Math.min(Math.max(size, 1), 100);
        int totalPages = (int) Math.max(1L, (total + s - 1) / s);
        int p = Math.min(Math.max(page, 1), totalPages);
        int from = Math.min((p - 1) * s, (int) total);
        int to = Math.min(from + s, (int) total);
        return new Window(p, s, from, to, totalPages);
    }

    /** Immutable view of everything pages/usuarios/tabla.html needs. */
    public record TableModel(String id, String baseUrl, List<Map<String, Object>> columnas,
                             List<?> filas, String sortKey, String sortDir, int page, int size,
                             long total, int totalPages, List<Integer> paginas,
                             Map<String, Object> filtros, String q) {

        public Map<String, Object> asMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", id);
            map.put("baseUrl", baseUrl);
            map.put("columnas", columnas);
            map.put("filas", filas);
            map.put("sortKey", sortKey);
            map.put("sortDir", sortDir);
            map.put("page", page);
            map.put("size", size);
            map.put("total", total);
            map.put("totalPages", totalPages);
            map.put("paginas", paginas);
            map.put("filtros", filtros);
            map.put("q", q);
            return map;
        }
    }
}
