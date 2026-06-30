package Models.Documentos;

import Models.ComprobantesEmitidos;
import Models.Detalles.DetalleServicio;
import Models.Encabezado.Encabezado;
import Models.Referencias.InformacionReferencia;
import Models.Resumen.ResumenFactura;
import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlRootElement;
import java.util.List;

/**
 * JAXB wrapper for Nota de Crédito Electrónica (NC).
 * Produces XML with root element {@code <NotaCreditoElectronica>} per Hacienda v4.4 spec.
 */
@XmlRootElement(name = "NotaCreditoElectronica",
                namespace = "https://tribunet.hacienda.go.cr/docs/esquemas/2017/v4.4/notaCreditoElectronica")
@XmlAccessorType(XmlAccessType.FIELD)
public class NotaCreditoElectronicaDocumento {

    @XmlElement(name = "Encabezado",
                namespace = "https://tribunet.hacienda.go.cr/docs/esquemas/2017/v4.4/notaCreditoElectronica")
    private Encabezado encabezado;

    @XmlElement(name = "DetalleServicio",
                namespace = "https://tribunet.hacienda.go.cr/docs/esquemas/2017/v4.4/notaCreditoElectronica")
    private DetalleServicio detalleServicio;

    @XmlElement(name = "InformacionReferencia",
                namespace = "https://tribunet.hacienda.go.cr/docs/esquemas/2017/v4.4/notaCreditoElectronica")
    private List<InformacionReferencia> informacionReferencia;

    @XmlElement(name = "ResumenFactura",
                namespace = "https://tribunet.hacienda.go.cr/docs/esquemas/2017/v4.4/notaCreditoElectronica")
    private ResumenFactura resumen;

    // JAXB requires no-arg constructor
    public NotaCreditoElectronicaDocumento() {}

    public NotaCreditoElectronicaDocumento(ComprobantesEmitidos ce) {
        this.encabezado = ce.getEncabezado();
        this.detalleServicio = ce.getDetalles();
        if (ce.getInformacionReferencia() != null && !ce.getInformacionReferencia().isEmpty()) {
            this.informacionReferencia = new java.util.ArrayList<>(ce.getInformacionReferencia());
        }
        this.resumen = ce.getResumen();
    }
}
