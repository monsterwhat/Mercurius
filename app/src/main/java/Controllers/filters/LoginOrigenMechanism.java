package Controllers.filters;

import io.quarkus.security.identity.IdentityProviderManager;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.identity.request.AuthenticationRequest;
import io.quarkus.security.identity.request.UsernamePasswordAuthenticationRequest;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import io.quarkus.vertx.http.runtime.security.ChallengeData;
import io.quarkus.vertx.http.runtime.security.HttpAuthenticationMechanism;
import io.quarkus.vertx.http.runtime.security.HttpCredentialTransport;
import io.smallrye.mutiny.Uni;
import io.vertx.ext.web.RoutingContext;
import jakarta.annotation.Priority;
import io.quarkus.vertx.http.runtime.security.FormAuthenticationMechanism;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.inject.Inject;
import java.util.Set;
import org.jboss.logging.Logger;

/**
 * Login CSRF protection for the form-auth endpoint.
 *
 * <p>{@code POST /j_security_check} is served by Quarkus' form mechanism,
 * which consumes the request before the main router (so no Vert.x
 * {@code @RouteFilter} sees it) and outside permission evaluation (so no
 * {@code HttpSecurityPolicy} — custom or built-in — is consulted either; both
 * were tried and verified not to fire). The authentication mechanism chain is
 * the only layer that observes this POST, hence a decorator here.</p>
 *
 * <p>Without a check, any site can auto-submit a login form with
 * attacker-chosen credentials and log the victim's browser into an account the
 * attacker owns; everything the victim then types into the app is visible to
 * the attacker. (The session cookie's {@code SameSite=Strict} does not stop
 * this: the forged POST is a top-level navigation whose {@code Set-Cookie}
 * response is honoured.)</p>
 *
 * <p>This decorator runs first (priority above every built-in mechanism) and
 * does one thing: a cross-origin login POST fails authentication, so no
 * session is ever minted for it. Everything else — all non-login requests, and
 * same-origin or headerless logins — is declined ({@code null}), and the chain
 * continues to the built-in mechanisms untouched. There is deliberately no
 * delegation to the form mechanism and no dependency on its (deprecated)
 * implementation class.</p>
 *
 * <p>Same-origin is defined exactly as in {@link #mismoOrigen}: the
 * {@code Origin} header, or {@code Referer} when {@code Origin} is absent, must
 * belong to this application's own origin. When NEITHER header is present the
 * request is declined to the normal chain (permitted): non-browser clients and
 * privacy-hardened browsers omit both, and an attacker driving a real browser
 * cannot withhold {@code Origin} on a POST.</p>
 */
@Alternative
@Priority(2100)
@ApplicationScoped
public class LoginOrigenMechanism implements HttpAuthenticationMechanism {

    private static final Logger LOG = Logger.getLogger(LoginOrigenMechanism.class);

    /** The form mechanism's fixed endpoint name — not a context path. */
    private static final String LOGIN_PATH_SUFFIX = "/j_security_check";

    /**
     * The built-in form mechanism, delegated to for every login this gate
     * allows through.
     *
     * <p>Declining (returning {@code null}) is NOT sufficient here: once this
     * decorator claims the form credential type at top priority, Quarkus routes
     * form authentication through it, and a decline surfaces as a 400 instead
     * of falling through to the built-in. So allowed logins are explicitly
     * handed to the delegate, and only cross-origin ones fail.</p>
     */
    @Inject
    FormAuthenticationMechanism form;

    @Override
    public Uni<SecurityIdentity> authenticate(RoutingContext ctx,
                                              IdentityProviderManager identityProviderManager) {
        if (!esLoginPost(ctx)) {
            // Not a login attempt (session-cookie replays, API calls, page
            // loads): hand straight to the form mechanism. Declining here is
            // NOT neutral — once this decorator claims the form credential type
            // at top priority, Quarkus routes that credential family through it
            // exclusively, and a decline surfaces as 401 instead of falling
            // through (verified: session replay broke). So everything this gate
            // does not explicitly block is delegated, never declined.
            return form.authenticate(ctx, identityProviderManager);
        }
        String origin = ctx.request().getHeader("Origin");
        String referencia = origin != null ? origin : ctx.request().getHeader("Referer");
        if (referencia == null || referencia.isBlank()) {
            return form.authenticate(ctx, identityProviderManager);
        }
        if (!mismoOrigen(referencia, origenEsperado(ctx))) {
            // Cross-origin login: return ANONYMOUS, not a failure. A thrown
            // exception is treated as "this mechanism declines" and the chain
            // falls through to the built-in form mechanism, which then mints
            // the attacker's session anyway (verified). A non-null identity —
            // even anonymous — STOPS the chain, so form never sees the forged
            // POST and no session is created. The anonymous POST then hits the
            // normal permission layer, which challenges it like any anonymous
            // request instead of logging the victim in as the attacker.
            LOG.warn("Inicio de sesion entre sitios rechazado: Origin/Referer fuera del origen propio"
                    + " | source=LoginOrigenMechanism.authenticate()");
            return Uni.createFrom().item(QuarkusSecurityIdentity.builder()
                    .setAnonymous(true)
                    .build());
        }
        return form.authenticate(ctx, identityProviderManager);
    }

    @Override
    public Uni<ChallengeData> getChallenge(RoutingContext ctx) {
        return form.getChallenge(ctx);
    }

    @Override
    public Set<Class<? extends AuthenticationRequest>> getCredentialTypes() {
        // Must be a type an IdentityProvider actually supports (Quarkus fails
        // the request when a mechanism names an unbacked type). The form login
        // posts username+password, so this mirrors the form mechanism's own
        // credential. This gate never builds the request itself — it only
        // fails cross-origin attempts and declines everything else.
        return Set.of(UsernamePasswordAuthenticationRequest.class);
    }

    @Override
    public Uni<HttpCredentialTransport> getCredentialTransport(RoutingContext ctx) {
        return Uni.createFrom().nullItem();
    }

    private static boolean esLoginPost(RoutingContext ctx) {
        return "POST".equalsIgnoreCase(ctx.request().method().name())
                && ctx.normalizedPath().endsWith(LOGIN_PATH_SUFFIX);
    }

    /**
     * The origin this request claims to target, e.g.
     * {@code https://inventario.ejemplo.cr}. Proxy headers win when present so
     * a TLS-terminating reverse proxy does not turn every legitimate login into
     * a mismatch (the app would see {@code http} while the browser sent
     * {@code https}).
     */
    static String origenEsperado(RoutingContext ctx) {
        String esquema = ctx.request().getHeader("X-Forwarded-Proto");
        if (esquema == null || esquema.isBlank()) {
            esquema = ctx.request().scheme();
        } else {
            int coma = esquema.indexOf(',');
            esquema = (coma >= 0 ? esquema.substring(0, coma) : esquema).trim();
        }
        String anfitrion = ctx.request().getHeader("X-Forwarded-Host");
        if (anfitrion == null || anfitrion.isBlank()) {
            anfitrion = ctx.request().getHeader("Host");
        } else {
            int coma = anfitrion.indexOf(',');
            anfitrion = (coma >= 0 ? anfitrion.substring(0, coma) : anfitrion).trim();
        }
        if (anfitrion == null || anfitrion.isBlank()) {
            anfitrion = ctx.request().authority() != null
                    ? ctx.request().authority().host()
                    : "localhost";
        }
        return normalizarOrigen(esquema + "://" + anfitrion);
    }

    /**
     * Whether {@code candidato} (an {@code Origin} value or a {@code Referer}
     * URL) belongs to {@code esperado}. Comparison is case-insensitive on
     * scheme and host, with default ports dropped, so proxies and explicit
     * ports do not cause false rejections.
     */
    public static boolean mismoOrigen(String candidato, String esperado) {
        if (candidato == null || esperado == null) {
            return false;
        }
        try {
            return normalizarOrigen(candidato).equalsIgnoreCase(esperado);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static String normalizarOrigen(String origen) {
        java.net.URI uri = java.net.URI.create(origen.trim());
        String esquema = uri.getScheme() != null ? uri.getScheme().toLowerCase(java.util.Locale.ROOT) : "";
        String anfitrion = uri.getHost() != null ? uri.getHost().toLowerCase(java.util.Locale.ROOT) : "";
        int puerto = uri.getPort();
        boolean porDefecto = puerto < 0
                || ("http".equals(esquema) && puerto == 80)
                || ("https".equals(esquema) && puerto == 443);
        return esquema + "://" + anfitrion + (porDefecto ? "" : ":" + puerto);
    }
}
