package Services.auth;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import io.vertx.ext.web.Router;
import jakarta.enterprise.event.Observes;
import io.vertx.core.http.Cookie;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Rejects a replayed form-auth cookie.
 *
 * <p>Runs before the REST security chain: if the request carries the
 * {@code quarkus-credential} cookie of a session that was logged out, the
 * cookie is expired and the request is answered as unauthenticated (401 for
 * API paths, redirect to the login page for everything else).</p>
 *
 * <p>Without this, {@code POST /api/app/auth/logout} is cosmetic with
 * stateless (encrypted-cookie) sessions: the server has no session table, so
 * the old cookie keeps authenticating.</p>
 */
@ApplicationScoped
public class LogoutDenylistFilter {

    private static final Logger LOG = Logger.getLogger(LogoutDenylistFilter.class);

    @Inject
    SesionCerradaService sesionesCerradas;

    @Inject
    @ConfigProperty(name = "quarkus.http.auth.form.login-page", defaultValue = "/login")
    String loginPage;

    void init(@Observes Router router) {
        // Very low order: this must run before Quarkus' security handler, which
        // is what turns the credential cookie into a SecurityIdentity. It also
        // has to be a BLOCKING handler: the tombstone lookup is a JPA read, and
        // on the IO thread that throws (and the fail-open path would let the
        // replayed cookie through).
        router.route().order(-10000).blockingHandler(this::filtrar);
    }

    private void filtrar(RoutingContext rc) {
        Cookie cookie = rc.request().getCookie(SesionCerradaService.COOKIE_NAME);
        if (cookie == null) {
            rc.next();
            return;
        }
        boolean cerrada;
        try {
            cerrada = sesionesCerradas.estaCerrada(cookie.getValue());
        } catch (RuntimeException e) {
            // Fail open on an infrastructure error: a login outage is worse
            // than a stale cookie, and the error is logged for triage.
            LOG.error("Falló la comprobación de sesiones cerradas; se deja pasar la petición: "
                    + e.getMessage() + " | source=LogoutDenylistFilter.filtrar()", e);
            rc.next();
            return;
        }
        if (!cerrada) {
            rc.next();
            return;
        }

        rc.response().addCookie(Cookie.cookie(SesionCerradaService.COOKIE_NAME, "").setMaxAge(0).setPath("/"));
        String path = rc.normalizedPath();
        if (path.startsWith("/api/") || path.contains("/api/")) {
            rc.response()
                    .setStatusCode(401)
                    .putHeader("Content-Type", "application/json; charset=UTF-8")
                    .end("{\"data\":null,\"error\":{\"code\":\"SESSION_CLOSED\","
                            + "\"message\":\"La sesión se cerró; inicie sesión de nuevo\"}}");
            return;
        }
        rc.response().setStatusCode(303).putHeader("Location", loginPage).end();
    }
}
