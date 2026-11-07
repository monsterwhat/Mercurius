package Controllers;

import io.quarkus.vertx.web.Route;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Bare-root redirect. The application is deployed at the server root
 * ({@code quarkus.http.root-path=/}), so this raw Vert.x route and
 * {@link RootRedirectResource} both answer {@code /}. The route stays as a
 * direct safety net should the JAX-RS root resource ever be pre-empted, and
 * sends bare-root hits to the dashboard.
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
                .putHeader("Location", "/app/dashboard")
                .end();
    }
}
