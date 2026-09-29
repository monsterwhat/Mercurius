package Controllers;

import Services.QuickActionsService;
import Models.AtajoUsuario;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;

import java.util.List;

/**
 * Per-operator UI shortcuts ("acciones rapidas").
 *
 * <p><b>Owner is always the authenticated caller, never the request.</b> Every
 * method here used to take the target user from a path parameter or a JSON body
 * and act on it directly, with no {@code @RolesAllowed} and no ownership check,
 * so any holder of a valid API token could enumerate and rewrite any operator's
 * shortcuts — including the two write endpoints keyed only by an entity id
 * ({@code /shortcuts/{id}/favorite}, {@code /usage}, {@code DELETE
 * /shortcuts/{id}}), which had no user reference at all to check against.</p>
 *
 * <p>The rule now enforced everywhere:</p>
 * <ul>
 *   <li>A {@code {username}} path parameter is honoured only when it equals the
 *       authenticated principal. A mismatch is 403, not an empty result — a
 *       silent empty list would look like "this user has no shortcuts" and hide
 *       the authorisation boundary.</li>
 *   <li>{@code addShortcut} overwrites the body's {@code username} with the
 *       caller's, so a body cannot plant a shortcut under someone else.</li>
 *   <li>By-id operations load the row and compare its {@code username} to the
 *       caller, so guessing an id is not enough.</li>
 * </ul>
 *
 * <p>{@code PublicApiJwtFilter} already requires a token for this prefix. Note
 * that the principal there is an API client code, not an operator username, so
 * an API client calling these gets 403 — correctly: these are per-operator UI
 * state, not a client-facing API resource.</p>
 */
@Path("/api/quick-actions")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class QuickActionsController {

    @Inject @Nonnull
    QuickActionsService quickActionsService;

    @Context @Nullable
    SecurityContext securityContext;

    /** Authenticated principal, or {@code null} when anonymous. */
    @Nullable
    private String caller() {
        return securityContext == null || securityContext.getUserPrincipal() == null
                ? null
                : securityContext.getUserPrincipal().getName();
    }

    /**
     * 403 when the caller is anonymous or is not the user named in the path.
     *
     * @return {@code true} when the request may proceed.
     */
    private boolean esPropietario(@Nullable String usernameSolicitado) {
        String caller = caller();
        if (caller == null || caller.isBlank()) {
            return false;
        }
        return usernameSolicitado == null || usernameSolicitado.equals(caller);
    }

    private static Response prohibido() {
        return Response.status(Response.Status.FORBIDDEN)
                .entity("{\"error\": \"Solo puede consultar o modificar sus propias acciones rapidas\"}")
                .build();
    }

    private static Response noEncontrado() {
        return Response.status(Response.Status.NOT_FOUND)
                .entity("{\"error\": \"Accion rapida no encontrada\"}")
                .build();
    }

    /**
     * Loads a shortcut by id and confirms the caller owns it.
     *
     * <p>The id-keyed writes previously had no owner check whatsoever, so any
     * authenticated caller could favourite, count or delete anyone's shortcut by
     * incrementing the id.</p>
     */
    private @Nullable AtajoUsuario propioPorId(long id) {
        List<AtajoUsuario> candidatos = quickActionsService.getUserShortcuts(caller());
        if (candidatos == null) {
            return null;
        }
        for (AtajoUsuario atajo : candidatos) {
            if (atajo != null && atajo.getId() != null && atajo.getId() == id) {
                return atajo;
            }
        }
        return null;
    }

    @GET
    @Path("/shortcuts/{username}")
    @Nonnull
    public Response getUserShortcuts(@PathParam("username") @Nonnull String username) {
        if (!esPropietario(username)) {
            return prohibido();
        }
        try {
            List<AtajoUsuario> shortcuts = quickActionsService.getUserShortcuts(caller());
            return Response.ok(shortcuts).build();
        } catch (RuntimeException e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .entity("{\"error\": \"" + e.getMessage() + "\"}").build();
        }
    }

    @GET
    @Path("/favorites/{username}")
    @Nonnull
    public Response getFavoriteActions(@PathParam("username") @Nonnull String username) {
        if (!esPropietario(username)) {
            return prohibido();
        }
        try {
            List<AtajoUsuario> favorites = quickActionsService.getFavoriteActions(caller());
            return Response.ok(favorites).build();
        } catch (RuntimeException e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .entity("{\"error\": \"" + e.getMessage() + "\"}").build();
        }
    }

    @GET
    @Path("/most-used/{username}")
    @Nonnull
    public Response getMostUsedActions(
            @PathParam("username") @Nonnull String username,
            @QueryParam("limit") @DefaultValue("5") int limit) {
        if (!esPropietario(username)) {
            return prohibido();
        }
        try {
            List<AtajoUsuario> mostUsed = quickActionsService.getMostUsedActions(caller(), limit);
            return Response.ok(mostUsed).build();
        } catch (RuntimeException e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .entity("{\"error\": \"" + e.getMessage() + "\"}").build();
        }
    }

    @POST
    @Path("/shortcuts")
    @Nonnull
    public Response addShortcut(@Nonnull AtajoUsuario shortcut) {
        String caller = caller();
        if (caller == null || caller.isBlank()) {
            return prohibido();
        }
        try {
            // The body's username used to be trusted, which let a caller plant a
            // shortcut in another operator's account. The owner is the caller.
            shortcut.setUsername(caller);
            AtajoUsuario created = quickActionsService.addShortcut(shortcut);
            return Response.ok(created).build();
        } catch (RuntimeException e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .entity("{\"error\": \"" + e.getMessage() + "\"}").build();
        }
    }

    @PUT
    @Path("/shortcuts/{id}/favorite")
    @Nonnull
    public Response toggleFavorite(@PathParam("id") @Nonnull Long id) {
        if (propioPorId(id) == null) {
            return noEncontrado();
        }
        try {
            AtajoUsuario updated = quickActionsService.toggleFavorite(id);
            return Response.ok(updated).build();
        } catch (RuntimeException e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .entity("{\"error\": \"" + e.getMessage() + "\"}").build();
        }
    }

    @PUT
    @Path("/shortcuts/{id}/usage")
    @Nonnull
    public Response incrementUsage(@PathParam("id") @Nonnull Long id) {
        if (propioPorId(id) == null) {
            return noEncontrado();
        }
        try {
            quickActionsService.incrementUsage(id);
            return Response.ok().build();
        } catch (RuntimeException e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .entity("{\"error\": \"" + e.getMessage() + "\"}").build();
        }
    }

    @DELETE
    @Path("/shortcuts/{id}")
    @Nonnull
    public Response deleteShortcut(@PathParam("id") @Nonnull Long id) {
        if (propioPorId(id) == null) {
            return noEncontrado();
        }
        try {
            quickActionsService.deleteShortcut(id);
            return Response.ok().build();
        } catch (RuntimeException e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .entity("{\"error\": \"" + e.getMessage() + "\"}").build();
        }
    }

    @POST
    @Path("/initialize/{username}")
    @Nonnull
    public Response initializeDefaults(@PathParam("username") @Nonnull String username) {
        if (!esPropietario(username)) {
            return prohibido();
        }
        try {
            quickActionsService.initializeDefaultShortcuts(caller());
            return Response.ok().build();
        } catch (RuntimeException e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .entity("{\"error\": \"" + e.getMessage() + "\"}").build();
        }
    }

    @PUT
    @Path("/reorder/{username}")
    @Nonnull
    public Response reorderShortcuts(@PathParam("username") @Nonnull String username, @Nonnull List<Long> shortcutIds) {
        if (!esPropietario(username)) {
            return prohibido();
        }
        try {
            quickActionsService.reorderShortcuts(caller(), shortcutIds);
            return Response.ok().build();
        } catch (RuntimeException e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .entity("{\"error\": \"" + e.getMessage() + "\"}").build();
        }
    }

    @GET
    @Path("/search/{username}")
    @Nonnull
    public Response quickSearch(
            @PathParam("username") @Nonnull String username,
            @QueryParam("q") @Nullable String query) {
        if (!esPropietario(username)) {
            return prohibido();
        }
        try {
            List<QuickActionsService.QuickSearchResult> results = quickActionsService.quickSearch(query, caller());
            return Response.ok(results).build();
        } catch (RuntimeException e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .entity("{\"error\": \"" + e.getMessage() + "\"}").build();
        }
    }
}
