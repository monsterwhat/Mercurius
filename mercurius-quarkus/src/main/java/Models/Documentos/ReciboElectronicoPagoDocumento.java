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
 * JAXB wrapper for Recibo Electrónico de Pago (REP).
 * Produces XML with root element {@code <ReciboElectronicoPago>} per Hacienda v4.4 spec.
 */
@XmlRootElement(name = "ReciboElectronicoPago",
                namespace = "https://tribunet.hacienda.go.cr/docs/esquemas/2017/v4.4/reciboElectronicoPago")
@XmlAccessorType(XmlAccessType.FIELD)
public class ReciboElectronicoPagoDocumento {

    @XmlElement(name = "Encabezado",
                namespace = "https://tribunet.hacienda.go.cr/docs/esquemas/2017/v4.4/reciboElectronicoPago")
    private Encabezado encabezado;

    @XmlElement(name = "DetalleServicio",
                namespace = "https://tribunet.hacienda.go.cr/docs/esquemas/2017/v4.4/reciboElectronicoPago")
    private DetalleServicio detalleServicio;

    @XmlElement(name = "InformacionReferencia",
                namespace = "https://tribunet.hacienda.go.cr/docs/esquemas/2017/v4.4/reciboElectronicoPago")
    private List<InformacionReferencia> informacionReferencia;

    @XmlElement(name = "ResumenFactura",
                namespace = "https://tribunet.hacienda.go.cr/docs/esquemas/2017/v4.4/reciboElectronicoPago")
    private ResumenFactura resumen;

    // JAXB requires no-arg constructor
    public ReciboElectronicoPagoDocumento() {}

    public ReciboElectronicoPagoDocumento(ComprobantesEmitidos ce) {
        this.encabezado = ce.getEncabezado();
        this.detalleServicio = ce.getDetalles();
        if (ce.getInformacionReferencia() != null && !ce.getInformacionReferencia().isEmpty()) {
            this.informacionReferencia = new java.util.ArrayList<>(ce.getInformacionReferencia());
        }
        this.resumen = ce.getResumen();
    }
}
