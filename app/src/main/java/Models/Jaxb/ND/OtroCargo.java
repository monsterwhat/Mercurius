package Models.Jaxb.ND;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import java.math.BigDecimal;
import lombok.Data;

@XmlAccessorType(XmlAccessType.FIELD)
@Data
public class OtroCargo {
    @XmlElement(name = "TipoDocumentoOC")
    private String tipoDocumentoOC;

    @XmlElement(name = "TipoDocumentoOtros")
    private String tipoDocumentoOTROS;

    @XmlElement(name = "IdentificacionTercero")
    private IdentificacionTercero identificacionTercero;

    @XmlElement(name = "NombreTercero")
    private String nombreTercero;

    @XmlElement(name = "Detalle")
    private String detalle;

    @XmlElement(name = "PorcentajeOC")
    private BigDecimal porcentajeOC;

    @XmlElement(name = "MontoCargo")
    private BigDecimal montoCargo;

    public OtroCargo() {}

    public OtroCargo(Models.Detalles.OtroCargo src) {
        if (src != null) {
            this.tipoDocumentoOC = src.getTipoDocumentoOC();
            this.tipoDocumentoOTROS = src.getTipoDocumentoOTROS();
            this.nombreTercero = src.getNombreTercero();
            this.detalle = src.getDetalle();
            this.porcentajeOC = src.getPorcentajeOC();
            this.montoCargo = src.getMontoCargo();
            if (src.getIdentificacionTercero() != null)
                this.identificacionTercero = new IdentificacionTercero(src.getIdentificacionTercero());
        }
    }
}
