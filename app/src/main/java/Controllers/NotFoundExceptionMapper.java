package Controllers;

import io.quarkus.security.identity.SecurityIdentity;
import jakarta.inject.Inject;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import java.net.URI;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;

/**
 * Auto-404: any unmatched JAX-RS path (no @Path) is mapped here.
 * - /api/* -> JSON 404 (so API clients / tests keep proper 404 envelope)
 * - anonymous -> 303 to /Mercurius/login (so deep-links/logged-out bookmarks land on login)
 * - authenticated -> 303 to /Mercurius/app (dashboard landing)
 *
 * <p>Both browser outcomes are redirects rather than a rendered 404 body: a
 * logged-out bookmark belongs on the login page, and a stale link typed by a
 * signed-in operator belongs on the dashboard instead of a dead page. The
 * /api/* branch keeps a real JSON 404 so API clients can distinguish
 * "no such resource" from a navigation.
 */
@Provider
@jakarta.enterprise.context.ApplicationScoped
public class NotFoundExceptionMapper implements ExceptionMapper<NotFoundException> {

    @Inject
    SecurityIdentity identity;

    @Context
    UriInfo uriInfo;

    @Override
    public Response toResponse(NotFoundException exception) {
        String path = uriInfo != null ? uriInfo.getPath() : "";
        String normalized = path == null ? "" : path;
        boolean isApi = normalized.startsWith("api/") || normalized.startsWith("api%2F");
        if (!isApi && normalized.startsWith("/api/")) {
            isApi = true;
        }
        if (isApi) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity("{\"code\":\"NOT_FOUND\",\"message\":\"Recurso no encontrado: /" + normalized + "\"}")
                    .type(MediaType.APPLICATION_JSON)
                    .build();
        }
        boolean anonymous = identity == null || identity.isAnonymous();
        if (anonymous) {
            return Response.seeOther(URI.create("/Mercurius/login")).build();
        }
        return Response.seeOther(URI.create("/Mercurius/app")).build();
    }

    @ServerExceptionMapper
    public Response mapNotFound(NotFoundException exception) {
        return toResponse(exception);
    }
}
