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
import java.util.ArrayList;
import java.util.List;

/**
 * JAXB wrapper for Nota de Débito Electrónica (ND).
 * Produces XML with root element {@code <NotaDebitoElectronica>} per Hacienda v4.4 spec.
 */
@XmlRootElement(name = "NotaDebitoElectronica",
                namespace = "https://tribunet.hacienda.go.cr/docs/esquemas/2017/v4.4/notaDebitoElectronica")
@XmlAccessorType(XmlAccessType.FIELD)
public class NotaDebitoElectronicaDocumento {

    @XmlElement(name = "Encabezado",
                namespace = "https://tribunet.hacienda.go.cr/docs/esquemas/2017/v4.4/notaDebitoElectronica")
    private Encabezado encabezado;

    @XmlElement(name = "DetalleServicio",
                namespace = "https://tribunet.hacienda.go.cr/docs/esquemas/2017/v4.4/notaDebitoElectronica")
    private DetalleServicio detalleServicio;

    @XmlElement(name = "InformacionReferencia",
                namespace = "https://tribunet.hacienda.go.cr/docs/esquemas/2017/v4.4/notaDebitoElectronica")
    private List<InformacionReferencia> informacionReferencia;

    @XmlElement(name = "ResumenFactura",
                namespace = "https://tribunet.hacienda.go.cr/docs/esquemas/2017/v4.4/notaDebitoElectronica")
    private ResumenFactura resumen;

    // JAXB requires no-arg constructor
    public NotaDebitoElectronicaDocumento() {}

    public NotaDebitoElectronicaDocumento(ComprobantesEmitidos ce) {
        this.encabezado = ce.getEncabezado();
        this.detalleServicio = ce.getDetalles();
        if (ce.getInformacionReferencia() != null && !ce.getInformacionReferencia().isEmpty()) {
            this.informacionReferencia = new ArrayList<>(ce.getInformacionReferencia());
        }
        this.resumen = ce.getResumen();
    }
}
