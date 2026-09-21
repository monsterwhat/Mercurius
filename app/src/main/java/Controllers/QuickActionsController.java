package Controllers;

import Services.QuickActionsService;
import Models.UserShortcut;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;

@Path("/api/quick-actions")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class QuickActionsController {

    @Inject @Nonnull
    QuickActionsService quickActionsService;

    @GET
    @Path("/shortcuts/{username}")
    @Nonnull
    public Response getUserShortcuts(@PathParam("username") @Nonnull String username) {
        try {
            List<UserShortcut> shortcuts = quickActionsService.getUserShortcuts(username);
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
        try {
            List<UserShortcut> favorites = quickActionsService.getFavoriteActions(username);
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
        try {
            List<UserShortcut> mostUsed = quickActionsService.getMostUsedActions(username, limit);
            return Response.ok(mostUsed).build();
        } catch (RuntimeException e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .entity("{\"error\": \"" + e.getMessage() + "\"}").build();
        }
    }

    @POST
    @Path("/shortcuts")
    @Nonnull
    public Response addShortcut(@Nonnull UserShortcut shortcut) {
        try {
            UserShortcut created = quickActionsService.addShortcut(shortcut);
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
        try {
            UserShortcut updated = quickActionsService.toggleFavorite(id);
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
        try {
            quickActionsService.initializeDefaultShortcuts(username);
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
        try {
            quickActionsService.reorderShortcuts(username, shortcutIds);
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
        try {
            List<QuickActionsService.QuickSearchResult> results = quickActionsService.quickSearch(query, username);
            return Response.ok(results).build();
        } catch (RuntimeException e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .entity("{\"error\": \"" + e.getMessage() + "\"}").build();
        }
    }
}
