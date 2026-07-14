package Models.Jaxb.ND;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import java.math.BigDecimal;
import lombok.Data;

@XmlAccessorType(XmlAccessType.FIELD)
@Data
public class ImpuestoSurtido {
    @XmlElement(name = "CodigoImpuestoSurtido")
    private String codigoImpuestoSurtido;

    @XmlElement(name = "CodigoImpuestoOtroSurtido")
    private String codigoImpuestoOTROSurtido;

    @XmlElement(name = "CodigoTarifaIvaSurtido")
    private String codigoTarifaIvaSurtido;

    @XmlElement(name = "TarifaSurtido")
    private BigDecimal tarifaSurtido;

    @XmlElement(name = "MontoImpuestoSurtido")
    private BigDecimal montoImpuestoSurtido;

    public ImpuestoSurtido() {}

    public ImpuestoSurtido(Models.Detalles.ImpuestoSurtido src) {
        if (src != null) {
            this.codigoImpuestoSurtido = src.getCodigoImpuestoSurtido();
            this.codigoImpuestoOTROSurtido = src.getCodigoImpuestoOTROSurtido();
            this.codigoTarifaIvaSurtido = src.getCodigoTarifaIvaSurtido();
            this.tarifaSurtido = src.getTarifaSurtido();
            this.montoImpuestoSurtido = src.getMontoImpuestoSurtido();
        }
    }
}
