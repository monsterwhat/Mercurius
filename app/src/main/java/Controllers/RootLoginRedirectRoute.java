package Controllers;

import io.quarkus.vertx.web.Route;
import io.vertx.ext.web.RoutingContext;

/**
 * Bare-root redirect. The application lives under
 * {@code quarkus.http.root-path=/Mercurius}, so JAX-RS resources (including
 * RootRedirectResource) can never match a request to {@code /} itself — it
 * 404s before reaching the app router. This raw Vert.x route is absolute
 * (not root-path prefixed) and sends bare-root hits to the login page.
 */
public class RootLoginRedirectRoute {

    @Route(path = "/", methods = Route.HttpMethod.GET)
    void root(RoutingContext rc) {
        rc.response()
                .setStatusCode(302)
                .putHeader("Location", "/Mercurius/login")
                .end();
    }
}
