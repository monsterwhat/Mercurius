package Controllers;

import io.quarkus.security.identity.SecurityIdentity;
import jakarta.inject.Inject;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HEAD;
import jakarta.ws.rs.OPTIONS;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import java.net.URI;

@Path("/{remaining:.+}")
public class FallbackResource {

    @Inject
    SecurityIdentity identity;

    @Context
    UriInfo uriInfo;

    private Response handle(String path) {
        String normalized = path == null ? "" : path;
        if (uriInfo != null && uriInfo.getPath() != null) {
            normalized = uriInfo.getPath();
        }
        String requestPath = uriInfo != null ? uriInfo.getRequestUri().getPath() : "/" + normalized;
        // Legacy XHTML entry points that historically redirected to dashboard
        String p = normalized == null ? "" : normalized;
        if (p.equals("/") || p.isEmpty() || p.equals("index.html") || p.equals("index.xhtml")
                || p.equals("secured/index.xhtml") || p.equals("/index.html") || p.equals("/index.xhtml")
                || p.equals("/secured/index.xhtml") || requestPath.endsWith("/Mercurius/")
                || requestPath.endsWith("/Mercurius/index.html") || requestPath.endsWith("/Mercurius/index.xhtml")) {
            return Response.seeOther(URI.create("/app/dashboard")).build();
        }
        boolean isApi = normalized.contains("api/") || normalized.startsWith("api/") || normalized.startsWith("/api/");
        if (!isApi && normalized.contains("/api/")) {
            isApi = true;
        }
        // Use request URI path which includes /Mercurius prefix if present
        if (!isApi && requestPath.contains("/api/")) {
            isApi = true;
        }
        if (isApi) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity("{\"code\":\"NOT_FOUND\",\"message\":\"Recurso no encontrado: " + requestPath + "\"}")
                    .type(MediaType.APPLICATION_JSON)
                    .build();
        }
        boolean anonymous = identity == null || identity.isAnonymous();
        if (anonymous) {
            return Response.seeOther(URI.create("/login")).build();
        }
        return Response.seeOther(URI.create("/app")).build();
    }

    @GET
    public Response getFallback() {
        return handle(null);
    }

    @POST
    public Response postFallback() {
        return handle(null);
    }

    @PUT
    public Response putFallback() {
        return handle(null);
    }

    @DELETE
    public Response deleteFallback() {
        return handle(null);
    }

    @PATCH
    public Response patchFallback() {
        return handle(null);
    }

    @HEAD
    public Response headFallback() {
        return handle(null);
    }

    @OPTIONS
    public Response optionsFallback() {
        return handle(null);
    }
}
