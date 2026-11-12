package Services;

import static org.assertj.core.api.Assertions.assertThat;

import Models.ComprobantesEmitidos;
import java.io.File;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;
import Utils.Parsers.FacturaBackupXml;

/**
 * El XML respaldado por el sistema anterior se importa al modelo persistido:
 * encabezado con clave/fecha, líneas enlazadas y resumen, listo para guardarse
 * como venta.
 */
class FacturaImportParseTest {

    @Test
    void backupXmlUnmarshalsIntoEmittedInvoice() throws Exception {
        File xml = new File("src/test/resources/fixtures/reales/v4.3/fe-v43-01.xml");
        String contenido = Files.readString(xml.toPath());

        ComprobantesEmitidos ce = FacturaBackupXml.parse(contenido, "importado");

        assertThat(ce.getEncabezado()).isNotNull();
        assertThat(ce.getEncabezado().getClave()).isNotBlank();
        assertThat(ce.getEncabezado().getNumeroConsecutivo()).isNotBlank();
        assertThat(ce.getEncabezado().getFechaEmision()).isNotNull();
        assertThat(ce.getEncabezado().getCodigoDocumento()).isEqualTo("01");
        assertThat(ce.getDetalles()).isNotNull();
        assertThat(ce.getDetalles().getLineasDetalle()).isNotEmpty();
        assertThat(ce.getDetalles().getLineasDetalle().get(0).getDetalleServicio())
                .isSameAs(ce.getDetalles());
        assertThat(ce.getResumen()).isNotNull();
        assertThat(ce.getResumen().getTotalComprobante()).isNotNull();
        assertThat(ce.getUser()).isEqualTo("importado");
        assertThat(ce.getStatus()).isTrue();
        assertThat(ce.getHaciendaEstado()).isEqualTo("ACEPTADO");
        assertThat(ce.getHaciendaClave()).isEqualTo(ce.getEncabezado().getClave());
        assertThat(FacturaBackupXml.tipoDocumento(contenido)).isEqualTo("FacturaElectronica");
        assertThat(FacturaBackupXml.esVenta("FacturaElectronica")).isTrue();
        assertThat(FacturaBackupXml.esVenta("NotaCreditoElectronica")).isFalse();
    }
}
