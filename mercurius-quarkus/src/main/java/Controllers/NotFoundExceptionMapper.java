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

/**
 * Auto-404: any unmatched JAX-RS path (no @Path) is mapped here.
 * - /api/* -> JSON 404 (so API clients / tests keep proper 404 envelope)
 * - otherwise if anonymous -> 302 to /Mercurius/login (so deep-links/logged-out bookmarks land on login)
 * - otherwise authenticated -> 302 to /Mercurius/app (dashboard landing, avoids dead blank 404)
 */
@Provider
public class NotFoundExceptionMapper implements ExceptionMapper<NotFoundException> {

    @Inject
    SecurityIdentity identity;

    @Context
    UriInfo uriInfo;

    @Override
    public Response toResponse(NotFoundException exception) {
        String path = uriInfo != null ? uriInfo.getPath() : "";
        String normalized = path == null ? "" : path;
        // uriInfo path is without leading slash and without root-path prefix
        boolean isApi = normalized.startsWith("api/") || normalized.startsWith("api%2F");
        // also handle leading slash variant if container normalizes differently
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
}
