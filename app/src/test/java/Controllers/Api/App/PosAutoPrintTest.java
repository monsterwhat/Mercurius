package Controllers.Api.App;

import static org.assertj.core.api.Assertions.assertThat;

import Models.Clientes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("POS: impresión obligatoria salvo correo entregado")
class PosAutoPrintTest {

    private static Clientes cliente(String email) {
        Clientes c = new Clientes();
        c.setName("Cliente");
        c.setEmail(email);
        return c;
    }

    @Test
    void sinEmailSiempreImprime() {
        assertThat(PosResource.autoPrintObligatorio(null, true)).isTrue();
        assertThat(PosResource.autoPrintObligatorio(cliente(null), true)).isTrue();
        assertThat(PosResource.autoPrintObligatorio(cliente("  "), true)).isTrue();
    }

    @Test
    void conEmailYEnviadoLaImpresionEsOpcional() {
        assertThat(PosResource.autoPrintObligatorio(cliente("cliente@test.cr"), true)).isFalse();
    }

    @Test
    void conEmailPeroSinEnvioSigueSiendoObligatoria() {
        assertThat(PosResource.autoPrintObligatorio(cliente("cliente@test.cr"), false)).isTrue();
    }
}
