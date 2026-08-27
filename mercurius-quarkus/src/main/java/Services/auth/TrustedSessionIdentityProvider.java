package Services.auth;

import io.quarkus.security.AuthenticationFailedException;
import io.quarkus.security.identity.AuthenticationRequestContext;
import io.quarkus.security.identity.IdentityProvider;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.identity.request.TrustedAuthenticationRequest;
import io.quarkus.security.runtime.QuarkusPrincipal;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import Models.Users;

/**
 * Restores identity on every request that already carries the encrypted
 * form-auth session cookie (Quarkus issues a
 * {@link TrustedAuthenticationRequest} for the replay path).
 *
 * <p>Roles are re-derived LIVE from the current {@code Users.groupName} on
 * each request — matching the legacy {@code SessionController.is*()} checks
 * that never cached roles. A user deleted or disabled between requests fails
 * authentication here, killing the session.</p>
 */
@ApplicationScoped
public class TrustedSessionIdentityProvider implements IdentityProvider<TrustedAuthenticationRequest> {

    @Inject
    SessionAuthAdapter loginService;

    @Override
    public Class<TrustedAuthenticationRequest> getRequestType() {
        return TrustedAuthenticationRequest.class;
    }

    @Override
    public Uni<SecurityIdentity> authenticate(TrustedAuthenticationRequest request,
            AuthenticationRequestContext context) {
        return context.runBlocking(() -> authenticateBlocking(request));
    }

    private SecurityIdentity authenticateBlocking(TrustedAuthenticationRequest request) {
        String username = request.getPrincipal();
        Users user = loginService.findByUsername(username);
        if (user == null || Boolean.FALSE.equals(user.getStatus())) {
            throw new AuthenticationFailedException("Sesión inválida para: " + username);
        }
        return QuarkusSecurityIdentity.builder()
                .setPrincipal(new QuarkusPrincipal(username))
                .addRoles(UserRoleMapper.mapGroupNameToRoles(user.getGroupName()))
                .build();
    }
}
