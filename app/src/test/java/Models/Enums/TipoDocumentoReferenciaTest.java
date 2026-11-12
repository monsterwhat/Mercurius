package Models.Enums;

import static org.assertj.core.api.Assertions.assertThat;

import Models.ComprobantesEmitidos;
import Models.Encabezado.Encabezado;
import Models.Referencias.InformacionReferencia;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The reference catalog (Nota 10) is NOT the header document catalog:
 * header NC=02 / ND=03, but reference 02=ND / 03=NC; FEE and REP also change
 * from 05/10 to 19/20, and FEC has no dedicated code (99 + description).
 */
@DisplayName("TipoDocIR: catalogo oficial de referencia")
class TipoDocumentoReferenciaTest {

    @Test
    @DisplayName("Nota 10: 02 es debito y 03 es credito")
    void nota10DosEsDebitoTresEsCredito() {
        assertThat(Tipo_Documento_Referencia.fromCodigo("02"))
                .isEqualTo(Tipo_Documento_Referencia.NOTA_DEBITO_ELECTRONICA);
        assertThat(Tipo_Documento_Referencia.fromCodigo("03"))
                .isEqualTo(Tipo_Documento_Referencia.NOTA_CREDITO_ELECTRONICA);
    }

    @Test
    @DisplayName("cada CodigoDocumento se traduce al TipoDocIR que Hacienda espera")
    void mapeaCodigoDeEncabezadoATipoDocIR() {
        assertRef("01", "01", null);
        assertRef("02", "03", null); // NC encabezado -> NC referencia
        assertRef("03", "02", null); // ND encabezado -> ND referencia
        assertRef("04", "04", null);
        assertRef("05", "19", null); // FEE
        assertRef("08", "99", "Factura Electrónica de Compra"); // FEC sin codigo propio
        assertRef("10", "20", null); // REP
        assertRef(null, "99", "Otros");
    }

    @Test
    @DisplayName("InformacionReferencia usa el mapeo, no el CodigoDocumento crudo")
    void informacionReferenciaUsaElMapeo() {
        Encabezado encabezado = new Encabezado();
        encabezado.setCodigoDocumento("08");
        encabezado.setNumeroConsecutivo("00100001080000000001");
        encabezado.setFechaEmision(LocalDateTime.now());
        ComprobantesEmitidos original = new ComprobantesEmitidos();
        original.setEncabezado(encabezado);

        InformacionReferencia ref = InformacionReferencia.from(original,
                Tipo_CodigosReferencia.DEVOLUCION_MERCANCIA, "Devolucion", "02");

        assertThat(ref.getTipoDoc()).isEqualTo("99");
        assertThat(ref.getTipoDocRefOTRO()).isEqualTo("Factura Electrónica de Compra");
        assertThat(ref.getNumero()).isEqualTo("00100001080000000001");
    }

    private static void assertRef(String codigoDocumento, String tipoDoc, String otro) {
        Tipo_Documento_Referencia.ReferenciaTipoDoc ref =
                Tipo_Documento_Referencia.paraComprobanteOriginal(codigoDocumento);
        assertThat(ref.codigo()).isEqualTo(tipoDoc);
        assertThat(ref.tipoDocRefOtro()).isEqualTo(otro);
    }
}
