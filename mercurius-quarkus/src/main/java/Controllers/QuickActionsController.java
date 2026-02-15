package Controllers;

import Services.QuickActionsService;
import Models.UserShortcut;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;

@Path("/api/quick-actions")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class QuickActionsController {

    @Inject
    QuickActionsService quickActionsService;

    @GET
    @Path("/shortcuts/{username}")
    public Response getUserShortcuts(@PathParam("username") String username) {
        try {
            List<UserShortcut> shortcuts = quickActionsService.getUserShortcuts(username);
            return Response.ok(shortcuts).build();
        } catch (Exception e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .entity("{\"error\": \"" + e.getMessage() + "\"}").build();
        }
    }

    @GET
    @Path("/favorites/{username}")
    public Response getFavoriteActions(@PathParam("username") String username) {
        try {
            List<UserShortcut> favorites = quickActionsService.getFavoriteActions(username);
            return Response.ok(favorites).build();
        } catch (Exception e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .entity("{\"error\": \"" + e.getMessage() + "\"}").build();
        }
    }

    @GET
    @Path("/most-used/{username}")
    public Response getMostUsedActions(
            @PathParam("username") String username,
            @QueryParam("limit") @DefaultValue("5") int limit) {
        try {
            List<UserShortcut> mostUsed = quickActionsService.getMostUsedActions(username, limit);
            return Response.ok(mostUsed).build();
        } catch (Exception e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .entity("{\"error\": \"" + e.getMessage() + "\"}").build();
        }
    }

    @POST
    @Path("/shortcuts")
    public Response addShortcut(UserShortcut shortcut) {
        try {
            UserShortcut created = quickActionsService.addShortcut(shortcut);
            return Response.ok(created).build();
        } catch (Exception e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .entity("{\"error\": \"" + e.getMessage() + "\"}").build();
        }
    }

    @PUT
    @Path("/shortcuts/{id}/favorite")
    public Response toggleFavorite(@PathParam("id") Long id) {
        try {
            UserShortcut updated = quickActionsService.toggleFavorite(id);
            return Response.ok(updated).build();
        } catch (Exception e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .entity("{\"error\": \"" + e.getMessage() + "\"}").build();
        }
    }

    @PUT
    @Path("/shortcuts/{id}/usage")
    public Response incrementUsage(@PathParam("id") Long id) {
        try {
            quickActionsService.incrementUsage(id);
            return Response.ok().build();
        } catch (Exception e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .entity("{\"error\": \"" + e.getMessage() + "\"}").build();
        }
    }

    @DELETE
    @Path("/shortcuts/{id}")
    public Response deleteShortcut(@PathParam("id") Long id) {
        try {
            quickActionsService.deleteShortcut(id);
            return Response.ok().build();
        } catch (Exception e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .entity("{\"error\": \"" + e.getMessage() + "\"}").build();
        }
    }

    @POST
    @Path("/initialize/{username}")
    public Response initializeDefaults(@PathParam("username") String username) {
        try {
            quickActionsService.initializeDefaultShortcuts(username);
            return Response.ok().build();
        } catch (Exception e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .entity("{\"error\": \"" + e.getMessage() + "\"}").build();
        }
    }

    @PUT
    @Path("/reorder/{username}")
    public Response reorderShortcuts(@PathParam("username") String username, List<Long> shortcutIds) {
        try {
            quickActionsService.reorderShortcuts(username, shortcutIds);
            return Response.ok().build();
        } catch (Exception e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .entity("{\"error\": \"" + e.getMessage() + "\"}").build();
        }
    }

    @GET
    @Path("/search/{username}")
    public Response quickSearch(
            @PathParam("username") String username,
            @QueryParam("q") String query) {
        try {
            List<QuickActionsService.QuickSearchResult> results = quickActionsService.quickSearch(query, username);
            return Response.ok(results).build();
        } catch (Exception e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .entity("{\"error\": \"" + e.getMessage() + "\"}").build();
        }
    }
}
