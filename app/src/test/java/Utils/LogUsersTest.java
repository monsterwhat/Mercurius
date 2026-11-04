package Utils;

import static org.assertj.core.api.Assertions.assertThat;

import Models.Usuarios;
import org.junit.jupiter.api.Test;

/**
 * Los logs no deben incluir el {@code toString()} de Usuarios: con Lombok
 * {@code @Data} arrastra la contraseña. Solo se registra el nombre.
 */
class LogUsersTest {

    @Test
    void devuelveSoloElNombre() {
        Usuarios usuario = new Usuarios();
        usuario.setUsername("Admin");
        usuario.setPassword("hash-secreto");

        String nombre = LogUsers.nombre(usuario);

        assertThat(nombre).isEqualTo("Admin");
        assertThat(nombre).doesNotContain("hash-secreto");
    }

    @Test
    void sinUsuarioDevuelveAnonimo() {
        assertThat(LogUsers.nombre(null)).isEqualTo("anonimo");
        assertThat(LogUsers.nombre(new Usuarios())).isEqualTo("anonimo");
    }
}
