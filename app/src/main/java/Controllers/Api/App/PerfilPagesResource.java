package Controllers.Api.App;

import Models.DTO.ApiResponse;
import Models.DTO.UsersDTO;
import Models.Users;
import Services.LoginService;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.Nonnull;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.HashMap;
import java.util.Map;

import org.jboss.logging.Logger;

/**
 * HTML page of the Perfil module for the NEW Qute/HTMX app surface:
 * {@code GET /app/perfil} — the logged-in user's profile (username, email,
 * group, status) plus the changeName/changeEmail/changePassword forms.
 * This class only renders HTML; all mutations live in the JSON twin
 * {@link ProfileResource} ({@code /api/app/perfil}), same split as
 * Articulos/Usuarios.
 */
@Path("/app/perfil")
@Produces(MediaType.TEXT_HTML)
@RolesAllowed({"admin", "inventario", "facturacion", "tributacion", "usuario", "registro"})
public class PerfilPagesResource {
    private static final Logger LOG = Logger.getLogger(PerfilPagesResource.class);

    @Inject
    @Nonnull
    LoginService loginService;

    @Inject
    @Nonnull
    SecurityIdentity identity;

    @Inject
    @Nonnull
    @Location("pages/perfil/index")
    Template page;

    @GET
    public Response index() {
        try {
            Users user = currentUser();
            if (user == null) {
                return Response.status(Response.Status.UNAUTHORIZED)
                        .entity(ApiResponse.error("UNAUTHORIZED", "No autenticado"))
                        .build();
            }
            Map<String, Object> model = new HashMap<>();
            model.put("usuario", toDTO(user));
            TemplateInstance instance = page.instance();
            model.forEach(instance::data);
            return Response.ok(instance.render())
                    .type(MediaType.TEXT_HTML_TYPE.withCharset("UTF-8")).build();
        } catch (RuntimeException e) {
            LOG.warn("Error renderizando la pagina de perfil", e);
            return Response.serverError()
                    .entity(ApiResponse.error("INTERNAL_ERROR",
                            "No se pudo cargar el perfil"))
                    .build();
        }
    }

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

    private static UsersDTO toDTO(Users user) {
        return new UsersDTO(user.getId(), user.getUsername(), user.getEmail(),
                user.getGroupName(), user.getStatus());
    }
}