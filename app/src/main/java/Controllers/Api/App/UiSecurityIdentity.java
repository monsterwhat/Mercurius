package Controllers.Api.App;

import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Named;
import jakarta.inject.Inject;

/**
 * Intermediate {@code @Named} CDI bean that exposes the Quarkus
 * {@link SecurityIdentity} to Qute templates as
 * {@code {inject:securityIdentity...}}.
 *
 * <p><b>Why this class exists (documented approach, cited per task T11):</b>
 * the Quarkus Qute reference guide, section <i>"Injecting Beans Directly in
 * Templates"</i>, specifies that the {@code inject:}/{@code cdi:} namespaces
 * can only reference CDI beans annotated with {@code @Named}, and that to
 * access a bean which is not {@code @Named} one must create an intermediate
 * {@code @Named} bean that injects and exposes it. Quarkus' built-in
 * {@link SecurityIdentity} carries no {@code @Named} qualifier, so this
 * request-scoped wrapper is the documented way to reach it from templates.</p>
 *
 * <p><b>Role semantics (parity with the legacy JSF navbar):</b> the six role
 * tokens {@code admin, facturacion, inventario, usuario, tributacion,
 * registro} are exactly the {@code Users.groupName} substring tokens checked
 * by {@code Controllers.SessionController#isFacturacion...isAdmin} (lines
 * 141-187). The T12 identity provider maps group names through
 * {@code Services.auth.UserRoleMapper}, which grants an admin identity all
 * six roles; therefore {@link #hasRole(String)} answers identically to the
 * legacy {@code isX()} checks and templates/gate on it directly.</p>
 *
 * <p>Consumed by {@code templates/fragments/navbar.html}; safe for anonymous
 * requests ({@link #hasRole(String)} returns {@code false}).</p>
 */
@RequestScoped
@Named("securityIdentity")
public class UiSecurityIdentity {

    private final SecurityIdentity identity;

    @Inject
    UiSecurityIdentity(SecurityIdentity identity) {
        this.identity = identity;
    }

    /**
     * @return {@code true} when no authenticated user is present.
     */
    public boolean isAnonymous() {
        return identity == null || identity.isAnonymous();
    }

    /**
     * @return the authenticated principal name, or {@code null} when anonymous.
     */
    public String getUsername() {
        if (isAnonymous() || identity.getPrincipal() == null) {
            return null;
        }
        return identity.getPrincipal().getName();
    }

    /**
     * Mirrors the legacy {@code SessionController.isX()} checks: role tokens
     * are the lowercase groupName substrings, and admins hold every token.
     *
     * @param role one of admin/facturacion/inventario/usuario/tributacion/registro
     * @return {@code false} for anonymous identities, otherwise delegates to
     *         the security identity's role set.
     */
    public boolean hasRole(String role) {
        if (isAnonymous() || role == null || role.isBlank()) {
            return false;
        }
        return identity.hasRole(role);
    }
}
