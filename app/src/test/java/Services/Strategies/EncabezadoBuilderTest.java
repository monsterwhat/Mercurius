package Services.Strategies;

import static org.assertj.core.api.Assertions.assertThat;

import Models.Clientes;
import Models.Encabezado.Receptor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Normalizacion de la identificacion del receptor.
 *
 * <p>El Registro Nacional emite cedulas juridicas alfanumericas (p.ej.
 * {@code 3-101-A00001}) y el XSD v4.4 las acepta en
 * {@code Receptor/Identificacion/Numero}. La normalizacion debe quitar solo
 * separadores, nunca las letras: recortarlas a digitos cambiaria el ID
 * fiscal del cliente.</p>
 */
@DisplayName("EncabezadoBuilder: identificacion del receptor")
class EncabezadoBuilderTest {

    private static Clientes cliente(String idType, String idNumber) {
        Clientes cliente = new Clientes();
        cliente.setName("Cliente de prueba");
        cliente.setIdType(idType);
        cliente.setIdNumber(idNumber);
        return cliente;
    }

    @Test
    @DisplayName("cedula juridica alfanumerica conserva las letras")
    void cedulaJuridicaAlfanumericaConservaLetras() {
        Receptor receptor = EncabezadoBuilder.buildReceptor(
                cliente("Cédula Jurídica", "3-101-A00001"));

        assertThat(receptor.getIdentificacion()).isNotNull();
        assertThat(receptor.getIdentificacion().getTipo()).isEqualTo("02");
        assertThat(receptor.getIdentificacion().getNumero())
                .as("las letras son parte de la cedula y no se pueden recortar")
                .isEqualTo("3101A00001");
    }

    @Test
    @DisplayName("cedula numerica con espacios y guiones se normaliza a digitos")
    void cedulaNumericaConSeparadoresSeNormaliza() {
        Receptor receptor = EncabezadoBuilder.buildReceptor(
                cliente("Cédula Jurídica", "3 101 331332"));

        assertThat(receptor.getIdentificacion().getNumero()).isEqualTo("3101331332");
    }

    @Test
    @DisplayName("DIMEX con guiones conserva sus digitos")
    void dimexConGuionesConservaDigitos() {
        Receptor receptor = EncabezadoBuilder.buildReceptor(
                cliente("DIMEX", "1558-1234-5678"));

        assertThat(receptor.getIdentificacion().getTipo()).isEqualTo("03");
        assertThat(receptor.getIdentificacion().getNumero()).isEqualTo("155812345678");
    }

    @Test
    @DisplayName("tipo desconocido usa IdentificacionExtranjero sin tocar el numero")
    void tipoDesconocidoUsaIdentificacionExtranjero() {
        Receptor receptor = EncabezadoBuilder.buildReceptor(
                cliente(null, "P-123456"));

        assertThat(receptor.getIdentificacion()).isNull();
        assertThat(receptor.getIdentificacionExtranjero()).isEqualTo("P-123456");
    }

    @Test
    @DisplayName("numero nulo queda vacio, no revienta")
    void numeroNuloQuedaVacio() {
        Receptor receptor = EncabezadoBuilder.buildReceptor(
                cliente("Cédula Física", null));

        assertThat(receptor.getIdentificacion().getNumero()).isEmpty();
    }
}
