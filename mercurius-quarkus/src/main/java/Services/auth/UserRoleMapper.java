package Services.auth;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Maps the free-text {@code Users.groupName} column to Quarkus security roles,
 * preserving the exact observable behavior of the legacy hand-rolled checks in
 * {@code Controllers.SessionController#isFacturation/isInventarios/isUsuarios/
 * isTributacion/isRegistros/isAdmin} (lines 141-187).
 *
 * <p><b>Mapping rules (behavior parity contract):</b>
 * <ul>
 *   <li>The legacy checks are case-sensitive substring tests
 *       ({@code groupName.contains(token)}); this mapper keeps that semantics
 *       byte-for-byte — {@code "ADMIN"} matches nothing, only lowercase
 *       {@code "admin"} does.</li>
 *   <li>Role names are EXACTLY the legacy check tokens (singular):
 *       {@code admin, facturacion, inventario, usuario, tributacion, registro}.
 *       Consistency with existing views beats prettiness.</li>
 *   <li>Every legacy check is {@code contains(token) || isAdmin()}, i.e. an
 *       admin implicitly holds every capability. Therefore a groupName that
 *       contains {@code "admin"} maps to ALL six roles so that
 *       {@code SecurityIdentity.hasRole(x)} answers identically to the legacy
 *       {@code isX()} methods.</li>
 *   <li>{@code null}, empty or blank groupName yields an empty role set
 *       (never throws).</li>
 * </ul>
 *
 * <p>This class is a stateless static utility, matching the existing
 * {@code Utils.DiffUtils} style in this codebase.
 */
public final class UserRoleMapper {

    /** Role/token constants — role name == legacy substring token (case-sensitive). */
    public static final String ROLE_ADMIN = "admin";
    public static final String ROLE_FACTURACION = "facturacion";
    public static final String ROLE_INVENTARIO = "inventario";
    public static final String ROLE_USUARIO = "usuario";
    public static final String ROLE_TRIBUTACION = "tributacion";
    public static final String ROLE_REGISTRO = "registro";

    private UserRoleMapper() {
        // static utility
    }

    /**
     * Maps a raw {@code Users.groupName} value to the Quarkus role set.
     *
     * @param groupName raw database value, e.g. {@code "admin"},
     *                  {@code "facturacion,usuario"} or the JSF
     *                  {@code Arrays.toString} form {@code "[tributacion, registro]"}
     * @return unmodifiable set of Quarkus roles; empty (never {@code null})
     *         when the input is {@code null}/blank or matches no token
     */
    public static Set<String> mapGroupNameToRoles(String groupName) {
        if (groupName == null || groupName.isBlank()) {
            return Set.of();
        }

        Set<String> roles = new LinkedHashSet<>();

        if (groupName.contains(ROLE_ADMIN)) {
            // Parity: SessionController grants every capability when isAdmin(),
            // so an admin identity carries all six roles.
            roles.add(ROLE_ADMIN);
            roles.add(ROLE_FACTURACION);
            roles.add(ROLE_INVENTARIO);
            roles.add(ROLE_USUARIO);
            roles.add(ROLE_TRIBUTACION);
            roles.add(ROLE_REGISTRO);
        } else {
            if (groupName.contains(ROLE_FACTURACION)) {
                roles.add(ROLE_FACTURACION);
            }
            if (groupName.contains(ROLE_INVENTARIO)) {
                roles.add(ROLE_INVENTARIO);
            }
            if (groupName.contains(ROLE_USUARIO)) {
                roles.add(ROLE_USUARIO);
            }
            if (groupName.contains(ROLE_TRIBUTACION)) {
                roles.add(ROLE_TRIBUTACION);
            }
            if (groupName.contains(ROLE_REGISTRO)) {
                roles.add(ROLE_REGISTRO);
            }
        }

        return Set.copyOf(roles);
    }
}
