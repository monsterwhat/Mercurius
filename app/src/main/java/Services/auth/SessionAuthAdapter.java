package Services.auth;

import Models.Users;
import Services.LoginService;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

/**
 * CDI bridge for authentication lookups performed OUTSIDE any active CDI
 * request context — most importantly from
 * {@link MercuriusIdentityProvider#authenticate}, which runs on a worker
 * thread where the {@code @PersistenceContext} EntityManager of
 * {@link LoginService} would otherwise raise
 * {@code ContextNotActiveException}.
 *
 * <p>{@code @ActivateRequestContext} activates a request context for each
 * delegated call, giving the JPA provider the context it needs.</p>
 */
@ApplicationScoped
public class SessionAuthAdapter {

    @Inject
    LoginService loginService;

    @ActivateRequestContext
    @Nullable
    public Users findByUsername(@Nonnull String username) {
        return loginService.findByUsername(username);
    }

    @ActivateRequestContext
    public boolean verifyPassword(@Nonnull String presentedPassword, @Nonnull String storedHash) {
        return loginService.verifyPassword(presentedPassword, storedHash);
    }

    @ActivateRequestContext
    @Transactional
    public void updatePassword(@Nonnull Users user, @Nonnull String newPassword) {
        loginService.updatePassword(user, newPassword);
    }
}
