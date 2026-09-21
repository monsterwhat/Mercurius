package Services.auth;

import java.util.Set;

import io.quarkus.security.AuthenticationFailedException;
import io.quarkus.security.identity.AuthenticationRequestContext;
import io.quarkus.security.identity.IdentityProvider;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.identity.request.UsernamePasswordAuthenticationRequest;
import io.quarkus.security.runtime.QuarkusPrincipal;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import Models.Users;
import Services.auth.SessionAuthAdapter;

/**
 * Quarkus {@link IdentityProvider} for username/password credentials that
 * delegates authentication to the existing, proven
 * {@link SessionAuthAdapter#findByUsername(String)} lookup and
 * {@link SessionAuthAdapter#verifyPassword(String, String)} BCrypt verification —
 * no credential logic is reimplemented here.
 *
 * <p><b>Wiring (per the Quarkus "Security customization" guide,
 * <a href="https://quarkus.io/guides/security-customization">security-customization</a>):</b>
 * a CDI bean implementing {@link IdentityProvider} is auto-discovered by the
 * Quarkus security runtime — no {@code @Alternative}, no
 * {@code META-INF/services} registration and no application.properties entry
 * is required. Once T13 enables form-based auth, requests posted to
 * {@code j_security_check} arrive here as
 * {@link UsernamePasswordAuthenticationRequest}.
 *
 * <p><b>Blocking model:</b> {@link SessionAuthAdapter} performs JPA queries (request context activated per call), so the
 * blocking work is wrapped in
 * {@link AuthenticationRequestContext#runBlocking(java.util.function.Supplier)}
 * as recommended by the same guide, keeping the IO/event-loop thread free.
 *
 * <p><b>Failure semantics:</b> unknown user, disabled account
 * ({@code status == Boolean.FALSE}) or wrong password all throw
 * {@link AuthenticationFailedException}, which the framework translates into a
 * failed form login (redirect to the error page once T13 wires it).
 * Note: {@code findByUsername} already filters {@code status = true}, so the
 * explicit disabled check is defense-in-depth for callers that hand us a
 * loaded-but-disabled entity path in the future.
 *
 * <p><b>Roles:</b> derived from {@code Users.groupName} via
 * {@link UserRoleMapper#mapGroupNameToRoles(String)}, preserving the legacy
 * {@code SessionController.is*()} truth table (admin ⇒ all six roles).
 */
@ApplicationScoped
public class MercuriusIdentityProvider implements IdentityProvider<UsernamePasswordAuthenticationRequest> {

    @Inject
    SessionAuthAdapter loginService;

    @Override
    public Class<UsernamePasswordAuthenticationRequest> getRequestType() {
        return UsernamePasswordAuthenticationRequest.class;
    }

    @Override
    public Uni<SecurityIdentity> authenticate(UsernamePasswordAuthenticationRequest request,
            AuthenticationRequestContext context) {
        return context.runBlocking(() -> authenticateBlocking(request));
    }

    private SecurityIdentity authenticateBlocking(UsernamePasswordAuthenticationRequest request) {
        String username = request.getUsername();

        char[] presentedPassword = request.getPassword() != null ? request.getPassword().getPassword() : null;
        if (presentedPassword == null || presentedPassword.length == 0) {
            throw new AuthenticationFailedException("Credenciales vacias para: " + username);
        }

        Users user = loginService.findByUsername(username);
        if (user == null) {
            // findByUsername filters status = true, so this also covers disabled users.
            throw new AuthenticationFailedException("Usuario desconocido o deshabilitado: " + username);
        }
        if (Boolean.FALSE.equals(user.getStatus())) {
            throw new AuthenticationFailedException("Usuario deshabilitado: " + username);
        }
        if (!loginService.verifyPassword(new String(presentedPassword), user.getPassword())) {
            throw new AuthenticationFailedException("Contrasena incorrecta para: " + username);
        }

        Set<String> roles = UserRoleMapper.mapGroupNameToRoles(user.getGroupName());
        return QuarkusSecurityIdentity.builder()
                .setPrincipal(new QuarkusPrincipal(username))
                .addRoles(roles)
                .build();
    }
}
