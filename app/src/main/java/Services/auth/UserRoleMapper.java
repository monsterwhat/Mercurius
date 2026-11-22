package Services.auth;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import org.jboss.logging.Logger;

/**
 * Maps the free-text {@code Usuarios.groupName} column to Quarkus security
 * roles.
 *
 * <p><b>Regla de seguridad.</b> El valor se interpreta como una lista de
 * <em>tokens exactos</em> separados por comas, con o sin corchetes porque el
 * alta antigua guardaba {@code Arrays.toString(...)}. Ya no se busca una
 * subcadena: {@code notadmin}, {@code administrador} y
 * {@code inventario_backup} no conceden nada. El caso se normaliza a
 * minusculas y se recortan los espacios alrededor de cada token.</p>
 *
 * <p>Un rol desconocido se ignora (no se concede) y se registra en DEBUG. Un
 * {@code admin} exacto expande a las seis capacidades, igual que hacia el
 * chequeo heredado {@code isAdmin()}.</p>
 */
public final class UserRoleMapper {

    private static final Logger LOG = Logger.getLogger(UserRoleMapper.class);

    public static final String ROLE_ADMIN = "admin";
    public static final String ROLE_FACTURACION = "facturacion";
    public static final String ROLE_INVENTARIO = "inventario";
    public static final String ROLE_USUARIO = "usuario";
    public static final String ROLE_TRIBUTACION = "tributacion";
    public static final String ROLE_REGISTRO = "registro";

    private static final Set<String> CAPACIDADES = Set.of(
            ROLE_ADMIN, ROLE_FACTURACION, ROLE_INVENTARIO,
            ROLE_USUARIO, ROLE_TRIBUTACION, ROLE_REGISTRO);

    private UserRoleMapper() {
        // static utility
    }

    /**
     * Maps a raw {@code Usuarios.groupName} value to the Quarkus role set.
     *
     * @param groupName raw database value, e.g. {@code "admin"},
     *                  {@code "facturacion,usuario"} or the JSF
     *                  {@code Arrays.toString} form {@code "[tributacion, registro]"}
     * @return unmodifiable set of Quarkus roles; empty (never {@code null})
     *         when the input is null/blank or contains no known token
     */
    public static Set<String> mapGroupNameToRoles(String groupName) {
        if (groupName == null || groupName.isBlank()) {
            return Set.of();
        }

        String normalizado = groupName.trim();
        if (normalizado.startsWith("[")) {
            normalizado = normalizado.substring(1);
        }
        if (normalizado.endsWith("]")) {
            normalizado = normalizado.substring(0, normalizado.length() - 1);
        }

        Set<String> roles = new LinkedHashSet<>();
        boolean admin = false;
        for (String parte : normalizado.split(",")) {
            String token = parte.trim().toLowerCase(Locale.ROOT);
            if (token.isEmpty()) {
                continue;
            }
            if (ROLE_ADMIN.equals(token)) {
                admin = true;
                continue;
            }
            if (CAPACIDADES.contains(token)) {
                roles.add(token);
            } else {
                LOG.debugf("Rol desconocido en groupName ignorado: '%s'", token);
            }
        }

        if (admin) {
            // Un admin exacto conserva la semantica del chequeo heredado:
            // todas las capacidades.
            return Set.copyOf(CAPACIDADES);
        }
        return Set.copyOf(roles);
    }
}
