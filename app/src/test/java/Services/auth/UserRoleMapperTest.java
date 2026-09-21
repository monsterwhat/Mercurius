package Services.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * Plain JUnit tests for {@link UserRoleMapper} (T12).
 *
 * <p>Parity contract with the legacy hand-rolled checks in
 * {@code Controllers.SessionController} lines 141-187:
 * case-sensitive substring tokens, singular role names, admin expansion to all
 * six roles (every legacy check is {@code contains(token) || isAdmin()}),
 * null/empty-safe.
 */
class UserRoleMapperTest {

    private static final Set<String> ALL_SIX_ROLES = Set.of(
            "admin", "facturacion", "inventario", "usuario", "tributacion", "registro");

    // --- single-token groups ---

    @Test
    void pureAdminGroupGrantsAllSixRoles() {
        Set<String> roles = UserRoleMapper.mapGroupNameToRoles("admin");

        assertThat(roles).containsExactlyInAnyOrderElementsOf(ALL_SIX_ROLES);
    }

    @Test
    void facturacionTokenMapsToSingularRole() {
        assertThat(UserRoleMapper.mapGroupNameToRoles("facturacion"))
                .containsExactly("facturacion");
    }

    @Test
    void inventarioTokenStaysSingularAsLegacyCheck() {
        // DECISION: role name equals today's check token ("inventario"), not a prettier plural.
        assertThat(UserRoleMapper.mapGroupNameToRoles("inventario"))
                .containsExactly("inventario");
    }

    @Test
    void usuarioTokenMapsToSingularRole() {
        assertThat(UserRoleMapper.mapGroupNameToRoles("usuario"))
                .containsExactly("usuario");
    }

    @Test
    void tributacionTokenMapsToSingularRole() {
        assertThat(UserRoleMapper.mapGroupNameToRoles("tributacion"))
                .containsExactly("tributacion");
    }

    @Test
    void registroTokenMapsToSingularRole() {
        assertThat(UserRoleMapper.mapGroupNameToRoles("registro"))
                .containsExactly("registro");
    }

    // --- multi-token groups ---

    @Test
    void mixedCommaSeparatedGroupMapsEachToken() {
        assertThat(UserRoleMapper.mapGroupNameToRoles("facturacion,usuario"))
                .containsExactlyInAnyOrder("facturacion", "usuario");
    }

    @Test
    void jsfArraysToStringFormatIsSupported() {
        // UsersController stores groupName via Arrays.toString(SelectedPuestos)
        assertThat(UserRoleMapper.mapGroupNameToRoles("[tributacion, registro]"))
                .containsExactlyInAnyOrder("tributacion", "registro");
    }

    @Test
    void administradorSubstringStillCountsAsAdmin() {
        // "administrador".contains("admin") == true — same as legacy isAdmin().
        assertThat(UserRoleMapper.mapGroupNameToRoles("administrador"))
                .containsExactlyInAnyOrderElementsOf(ALL_SIX_ROLES);
    }

    // --- case sensitivity preserved as-today ---

    @Test
    void upperCaseAdminDoesNotMatchAsToday() {
        // String.contains is case-sensitive; legacy behavior kept byte-for-byte.
        assertThat(UserRoleMapper.mapGroupNameToRoles("ADMIN")).isEmpty();
    }

    @Test
    void mixedCaseTokenDoesNotMatchAsToday() {
        assertThat(UserRoleMapper.mapGroupNameToRoles("Facturacion")).isEmpty();
    }

    // --- unknown / degenerate inputs ---

    @Test
    void unknownGroupYieldsEmptySet() {
        assertThat(UserRoleMapper.mapGroupNameToRoles("ventas")).isEmpty();
    }

    @Test
    void nullGroupYieldsEmptySetWithoutThrowing() {
        assertThat(UserRoleMapper.mapGroupNameToRoles(null)).isNotNull().isEmpty();
    }

    @Test
    void emptyAndBlankGroupsYieldEmptySet() {
        assertThat(UserRoleMapper.mapGroupNameToRoles("")).isEmpty();
        assertThat(UserRoleMapper.mapGroupNameToRoles("   ")).isEmpty();
    }

    // --- returned set contract ---

    @Test
    void returnedSetIsUnmodifiable() {
        Set<String> roles = UserRoleMapper.mapGroupNameToRoles("facturacion");

        assertThatThrownBy(() -> roles.add("admin"))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
