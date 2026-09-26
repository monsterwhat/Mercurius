package Controllers;

import io.quarkus.vertx.web.Route;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Bare-root redirect. The application lives under
 * {@code quarkus.http.root-path=/Mercurius}, so JAX-RS resources (including
 * RootRedirectResource) can never match a request to {@code /} itself — it
 * 404s before reaching the app router. This raw Vert.x route is absolute
 * (not root-path prefixed) and sends bare-root hits to the dashboard.
 *
 * <p>Answers 303 (seeOther) to the dashboard for anonymous and authenticated
 * callers alike, mirroring {@link RootRedirectResource} so both root hops
 * behave identically. An anonymous visitor is then bounced to /login by the
 * still-secured /app/* policy, carrying quarkus-redirect-location, so the
 * deep link replays after login (the bounce-back contract the auth journey
 * pins). {@code /} is listed as public in the permission policy so this route
 * is reachable instead of being pre-empted by the form-auth 302 challenge.
 */
@ApplicationScoped
public class RootLoginRedirectRoute {

    @Route(path = "/", methods = Route.HttpMethod.GET)
    void root(RoutingContext rc) {
        rc.response()
                .setStatusCode(303)
                .putHeader("Location", "/Mercurius/app/dashboard")
                .end();
    }
}
