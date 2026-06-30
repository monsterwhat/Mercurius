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
 * JAXB wrapper for Tiquete Electrónico (TE).
 * Produces XML with root element {@code <TiqueteElectronico>} per Hacienda v4.4 spec.
 */
@XmlRootElement(name = "TiqueteElectronico",
                namespace = "https://tribunet.hacienda.go.cr/docs/esquemas/2017/v4.4/tiqueteElectronico")
@XmlAccessorType(XmlAccessType.FIELD)
public class TiqueteElectronicoDocumento {

    @XmlElement(name = "Encabezado",
                namespace = "https://tribunet.hacienda.go.cr/docs/esquemas/2017/v4.4/tiqueteElectronico")
    private Encabezado encabezado;

    @XmlElement(name = "DetalleServicio",
                namespace = "https://tribunet.hacienda.go.cr/docs/esquemas/2017/v4.4/tiqueteElectronico")
    private DetalleServicio detalleServicio;

    @XmlElement(name = "ResumenFactura",
                namespace = "https://tribunet.hacienda.go.cr/docs/esquemas/2017/v4.4/tiqueteElectronico")
    private ResumenFactura resumen;

    // JAXB requires no-arg constructor
    public TiqueteElectronicoDocumento() {}

    public TiqueteElectronicoDocumento(ComprobantesEmitidos ce) {
        this.encabezado = ce.getEncabezado();
        this.detalleServicio = ce.getDetalles();
        this.resumen = ce.getResumen();
    }
}
