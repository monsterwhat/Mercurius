package Utils;

import Models.ConfiguracionAplicacion;
import Models.Usuarios;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Los snapshots de auditoria nunca deben exponer hashes ni secretos. */
class DiffUtilsRedactionTest {

    @Test
    void snapshotDeUsuarioNoIncluyePasswordNiHash() {
        Usuarios usuario = new Usuarios();
        usuario.setUsername("cajero");
        usuario.setPassword("$argon2id$v=19$m=65536,t=3,p=4$hash-secreto");

        String snapshot = DiffUtils.snapshotEntity(usuario);

        assertThat(snapshot).contains("cajero");
        assertThat(snapshot).doesNotContain("argon2");
        assertThat(snapshot).doesNotContain("hash-secreto");
        assertThat(snapshot.toLowerCase()).doesNotContain("password");
    }

    @Test
    void snapshotDeConfiguracionNoIncluyeSecretos() {
        ConfiguracionAplicacion config = new ConfiguracionAplicacion();
        config.setNombrePerfil("produccion");
        config.setContrasenaCorreo("clave-smtp");
        config.setCertificadoPassword("clave-p12");
        config.setHaciendaApiKey("api-key-hacienda");
        config.setAuthSessionKey("sesion-secreta");
        config.setHaciendaEncryptionKey("llave-cifrado");

        String snapshot = DiffUtils.snapshotEntity(config);

        assertThat(snapshot).contains("produccion");
        assertThat(snapshot).doesNotContain("clave-smtp");
        assertThat(snapshot).doesNotContain("clave-p12");
        assertThat(snapshot).doesNotContain("api-key-hacienda");
        assertThat(snapshot).doesNotContain("sesion-secreta");
        assertThat(snapshot).doesNotContain("llave-cifrado");
    }
}
