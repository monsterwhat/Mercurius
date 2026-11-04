package Utils;

import Models.Usuarios;
import jakarta.annotation.Nullable;

/**
 * Nombre de usuario seguro para logs.
 *
 * <p>{@code Usuarios} es un {@code @Data} de Lombok: su {@code toString()}
 * incluye la contraseña (hash y sal). Registrarlo con
 * {@code String.valueOf(currentUser())} escribía credenciales en el log en
 * cada acción. Esta clase devuelve solo el nombre.</p>
 */
public final class LogUsers {

    private LogUsers() {
    }

    /** Nombre del usuario, o {@code anonimo} si no hay usuario en el contexto. */
    public static String nombre(@Nullable Usuarios usuario) {
        return usuario == null || usuario.getUsername() == null
                ? "anonimo" : usuario.getUsername();
    }
}
