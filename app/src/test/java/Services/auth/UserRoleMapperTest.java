package Services.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * Contrato de {@link UserRoleMapper}: tokens exactos, sin coincidencias por
 * subcadena. Los casos {@code notadmin}, {@code administrador} e
 * {@code inventario_backup} existen precisamente para impedir que un privilegio
 * se conceda por accidente.
 */
class UserRoleMapperTest {

    private static final Set<String> ALL_SIX_ROLES = Set.of(
            "admin", "facturacion", "inventario", "usuario", "tributacion", "registro");

    @Test
    void pureAdminGroupGrantsAllSixRoles() {
        assertThat(UserRoleMapper.mapGroupNameToRoles("admin"))
                .containsExactlyInAnyOrderElementsOf(ALL_SIX_ROLES);
    }

    @Test
    void adminCaseIsNormalized() {
        assertThat(UserRoleMapper.mapGroupNameToRoles("ADMIN"))
                .containsExactlyInAnyOrderElementsOf(ALL_SIX_ROLES);
        assertThat(UserRoleMapper.mapGroupNameToRoles("Admin"))
                .containsExactlyInAnyOrderElementsOf(ALL_SIX_ROLES);
    }

    @Test
    void adminWithWhitespaceIsStillExactAdmin() {
        assertThat(UserRoleMapper.mapGroupNameToRoles("  admin  "))
                .containsExactlyInAnyOrderElementsOf(ALL_SIX_ROLES);
    }

    @Test
    void eachCapabilityMapsToItsExactRole() {
        assertThat(UserRoleMapper.mapGroupNameToRoles("facturacion")).containsExactly("facturacion");
        assertThat(UserRoleMapper.mapGroupNameToRoles("inventario")).containsExactly("inventario");
        assertThat(UserRoleMapper.mapGroupNameToRoles("usuario")).containsExactly("usuario");
        assertThat(UserRoleMapper.mapGroupNameToRoles("tributacion")).containsExactly("tributacion");
        assertThat(UserRoleMapper.mapGroupNameToRoles("registro")).containsExactly("registro");
    }

    @Test
    void mixedCommaSeparatedGroupMapsEachToken() {
        assertThat(UserRoleMapper.mapGroupNameToRoles("facturacion,usuario"))
                .containsExactlyInAnyOrder("facturacion", "usuario");
        assertThat(UserRoleMapper.mapGroupNameToRoles("admin,usuario"))
                .containsExactlyInAnyOrderElementsOf(ALL_SIX_ROLES);
    }

    @Test
    void jsfArraysToStringFormatIsSupported() {
        assertThat(UserRoleMapper.mapGroupNameToRoles("[tributacion, registro]"))
                .containsExactlyInAnyOrder("tributacion", "registro");
        assertThat(UserRoleMapper.mapGroupNameToRoles("[admin, usuario]"))
                .containsExactlyInAnyOrderElementsOf(ALL_SIX_ROLES);
    }

    @Test
    void whitespaceAroundTokensIsTrimmed() {
        assertThat(UserRoleMapper.mapGroupNameToRoles("  facturacion ,  inventario  "))
                .containsExactlyInAnyOrder("facturacion", "inventario");
    }

    @Test
    void notAdminIsNotAdmin() {
        assertThat(UserRoleMapper.mapGroupNameToRoles("notadmin")).isEmpty();
    }

    @Test
    void administradorIsNotAdminAnymore() {
        assertThat(UserRoleMapper.mapGroupNameToRoles("administrador")).isEmpty();
    }

    @Test
    void superadminBackupIsNotAdmin() {
        assertThat(UserRoleMapper.mapGroupNameToRoles("superadmin_backup")).isEmpty();
    }

    @Test
    void inventarioBackupIsNotInventario() {
        assertThat(UserRoleMapper.mapGroupNameToRoles("inventario_backup")).isEmpty();
        assertThat(UserRoleMapper.mapGroupNameToRoles("inventario_extra")).isEmpty();
    }

    @Test
    void unknownRoleIsIgnoredButKnownRolesStillWork() {
        assertThat(UserRoleMapper.mapGroupNameToRoles("inventario,ventas"))
                .containsExactly("inventario");
    }

    @Test
    void nullGroupYieldsEmptySetWithoutThrowing() {
        assertThat(UserRoleMapper.mapGroupNameToRoles(null)).isNotNull().isEmpty();
    }

    @Test
    void emptyAndBlankGroupsYieldEmptySet() {
        assertThat(UserRoleMapper.mapGroupNameToRoles("")).isEmpty();
        assertThat(UserRoleMapper.mapGroupNameToRoles("   ")).isEmpty();
        assertThat(UserRoleMapper.mapGroupNameToRoles("[,]")).isEmpty();
    }

    @Test
    void returnedSetIsUnmodifiable() {
        Set<String> roles = UserRoleMapper.mapGroupNameToRoles("facturacion");

        assertThatThrownBy(() -> roles.add("admin"))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
