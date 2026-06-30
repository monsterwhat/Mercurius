package Models.Documentos;

import Models.ComprobantesEmitidos;
import Models.Detalles.DetalleServicio;
import Models.Encabezado.Encabezado;
import Models.Resumen.ResumenFactura;
import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlRootElement;

/**
 * JAXB wrapper for Factura Electrónica (FE).
 * Produces XML with root element {@code <FacturaElectronica>} per Hacienda v4.4 spec.
 */
@XmlRootElement(name = "FacturaElectronica",
                namespace = "https://tribunet.hacienda.go.cr/docs/esquemas/2017/v4.4/facturaElectronica")
@XmlAccessorType(XmlAccessType.FIELD)
public class FacturaElectronicaDocumento {

    @XmlElement(name = "Encabezado",
                namespace = "https://tribunet.hacienda.go.cr/docs/esquemas/2017/v4.4/facturaElectronica")
    private Encabezado encabezado;

    @XmlElement(name = "DetalleServicio",
                namespace = "https://tribunet.hacienda.go.cr/docs/esquemas/2017/v4.4/facturaElectronica")
    private DetalleServicio detalleServicio;

    @XmlElement(name = "ResumenFactura",
                namespace = "https://tribunet.hacienda.go.cr/docs/esquemas/2017/v4.4/facturaElectronica")
    private ResumenFactura resumen;

    // JAXB requires no-arg constructor
    public FacturaElectronicaDocumento() {}

    public FacturaElectronicaDocumento(ComprobantesEmitidos ce) {
        this.encabezado = ce.getEncabezado();
        this.detalleServicio = ce.getDetalles();
        this.resumen = ce.getResumen();
    }
}
