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

/**
 * Dead-link catch-all: any path that no other JAX-RS resource claims.
 *
 * <p><b>Why the path template excludes the static subtrees.</b> This resource
 * used to be declared {@code @Path("/{remaining:.+}")}, which matches EVERY
 * path — including {@code /static/bundle/app-<hash>.js} and
 * {@code /resources/css/*.css}. A JAX-RS resource match pre-empts Vert.x
 * static-resource serving, so the catch-all answered before the asset could be
 * written, and its anonymous branch returned {@code seeOther("/login")}. The
 * observable symptom was an anonymous asset GET answering <b>303 to the login
 * page</b>, which the browser reports as a MIME error and which left the
 * login page unstyled.
 *
 * <p>That 303 was repeatedly misdiagnosed as a form-auth challenge and "fixed"
 * by adding more entries to {@code quarkus.http.auth.permission.public.paths}
 * (see a1b8eef). The permission config was correct all along — two tells give
 * the hijack away: a genuine form-auth challenge answers <b>302</b> and sets a
 * {@code quarkus-redirect-location} cookie, whereas this catch-all answers
 * <b>303</b> and sets only a {@code csrf-token} cookie; and the same 303 came
 * back for paths that match no file at all.
 *
 * <p>The negative lookaheads below hand {@code static/}, {@code resources/}
 * (and the Quarkus dev-ui {@code q/}) back to the static handler, so a real
 * asset is served and a missing one 404s. Everything else keeps the original
 * dead-link contract, pinned by StaticAssetContractTest.
 */
@Path("/{remaining:(?!static/|resources/|q/)(?!static$|resources$|q$).+}")
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
        // Signed-in operator on a stale link: bounce to the dashboard rather
        // than render a dead end. Same contract as NotFoundExceptionMapper, so
        // both not-found entry points answer identically.
        return Response.seeOther(URI.create("/Mercurius/app")).build();
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
