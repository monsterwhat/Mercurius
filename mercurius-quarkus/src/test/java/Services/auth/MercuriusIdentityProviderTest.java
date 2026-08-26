package Services.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import io.quarkus.security.AuthenticationFailedException;
import io.quarkus.security.credential.PasswordCredential;
import io.quarkus.security.identity.AuthenticationRequestContext;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.identity.request.UsernamePasswordAuthenticationRequest;
import io.smallrye.mutiny.Uni;
import Models.Users;
import Services.LoginService;

/**
 * Plain-Mockito unit tests for {@link MercuriusIdentityProvider} (T12).
 *
 * <p>Three-case acceptance matrix from the plan plus edge cases:
 * <ul>
 *   <li>correct credentials → SecurityIdentity with principal + mapped roles</li>
 *   <li>wrong password → {@link AuthenticationFailedException}</li>
 *   <li>disabled user ({@code status = Boolean.FALSE}) → {@link AuthenticationFailedException}</li>
 *   <li>unknown user → {@link AuthenticationFailedException}</li>
 * </ul>
 *
 * <p>{@link LoginService} is mocked — no database, no Quarkus boot. The
 * {@link AuthenticationRequestContext#runBlocking(Supplier)} stub executes the
 * supplier synchronously so the blocking path is exercised directly.
 */
@ExtendWith(MockitoExtension.class)
class MercuriusIdentityProviderTest {

    private static final String BCRYPT_HASH = "$2a$10$abcdefghijklmnopqrstuvABCDEFGHIJKLMNOPQRSTUVWXYZ01234";

    @Mock
    private LoginService loginService;

    @Mock
    private AuthenticationRequestContext context;

    @InjectMocks
    private MercuriusIdentityProvider provider;

    private Users activeUser;

    @BeforeEach
    void setUp() {
        activeUser = new Users();
        activeUser.setUsername("admin");
        activeUser.setPassword(BCRYPT_HASH);
        activeUser.setGroupName("admin");
        activeUser.setStatus(Boolean.TRUE);
    }

    /** Runs the provider end-to-end, executing the runBlocking supplier inline. */
    private SecurityIdentity authenticate(String username, char[] password) {
        when(context.runBlocking(any())).thenAnswer(invocation ->
                Uni.createFrom().item(((Supplier<SecurityIdentity>) invocation.getArgument(0)).get()));

        UsernamePasswordAuthenticationRequest request =
                new UsernamePasswordAuthenticationRequest(username, new PasswordCredential(password));
        return provider.authenticate(request, context).await().indefinitely();
    }

    // --- SPI contract ---

    @Test
    void requestTypeIsUsernamePassword() {
        assertThat(provider.getRequestType()).isEqualTo(UsernamePasswordAuthenticationRequest.class);
    }

    // --- success paths ---

    @Test
    void correctCredentialsBuildIdentityWithAllAdminRoles() {
        when(loginService.findByUsername("admin")).thenReturn(activeUser);
        when(loginService.verifyPassword("Mercurius@2024!", BCRYPT_HASH)).thenReturn(true);

        SecurityIdentity identity = authenticate("admin", "Mercurius@2024!".toCharArray());

        assertThat(identity.isAnonymous()).isFalse();
        assertThat(identity.getPrincipal().getName()).isEqualTo("admin");
        assertThat(identity.getRoles()).containsExactlyInAnyOrder(
                "admin", "facturacion", "inventario", "usuario", "tributacion", "registro");

        verify(loginService).findByUsername("admin");
        verify(loginService).verifyPassword("Mercurius@2024!", BCRYPT_HASH);
    }

    @Test
    void singleRoleUserGetsExactlyItsMappedRole() {
        Users vendedor = new Users();
        vendedor.setUsername("vendedor1");
        vendedor.setPassword(BCRYPT_HASH);
        vendedor.setGroupName("facturacion");
        vendedor.setStatus(Boolean.TRUE);

        when(loginService.findByUsername("vendedor1")).thenReturn(vendedor);
        when(loginService.verifyPassword("clave", BCRYPT_HASH)).thenReturn(true);

        SecurityIdentity identity = authenticate("vendedor1", "clave".toCharArray());

        assertThat(identity.getPrincipal().getName()).isEqualTo("vendedor1");
        assertThat(identity.getRoles()).containsExactly("facturacion");
    }

    // --- failure paths ---

    @Test
    void wrongPasswordThrowsAuthenticationFailedException() {
        when(loginService.findByUsername("admin")).thenReturn(activeUser);
        when(loginService.verifyPassword("incorrecta", BCRYPT_HASH)).thenReturn(false);

        assertThatThrownBy(() -> authenticate("admin", "incorrecta".toCharArray()))
                .isInstanceOf(AuthenticationFailedException.class);

        verify(loginService).findByUsername("admin");
        verify(loginService).verifyPassword("incorrecta", BCRYPT_HASH);
    }

    @Test
    void disabledUserThrowsAuthenticationFailedExceptionWithoutPasswordCheck() {
        activeUser.setStatus(Boolean.FALSE);
        when(loginService.findByUsername("admin")).thenReturn(activeUser);

        assertThatThrownBy(() -> authenticate("admin", "Mercurius@2024!".toCharArray()))
                .isInstanceOf(AuthenticationFailedException.class);

        verify(loginService).findByUsername("admin");
        verify(loginService, never()).verifyPassword(any(), any());
    }

    @Test
    void unknownUserThrowsAuthenticationFailedException() {
        when(loginService.findByUsername("fantasma")).thenReturn(null);

        assertThatThrownBy(() -> authenticate("fantasma", "cualquier".toCharArray()))
                .isInstanceOf(AuthenticationFailedException.class);

        verify(loginService).findByUsername("fantasma");
        verify(loginService, never()).verifyPassword(any(), any());
    }

    @Test
    void emptyPasswordCredentialThrowsAuthenticationFailedExceptionBeforeLookup() {
        assertThatThrownBy(() -> authenticate("admin", new char[0]))
                .isInstanceOf(AuthenticationFailedException.class);

        verify(loginService, never()).findByUsername(any());
        verify(loginService, never()).verifyPassword(any(), any());
    }
}
