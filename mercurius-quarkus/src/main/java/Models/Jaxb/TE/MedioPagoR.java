package Models.Jaxb.TE;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import java.math.BigDecimal;
import lombok.Data;

@XmlAccessorType(XmlAccessType.FIELD)
@Data
public class MedioPagoR {
    @XmlElement(name = "TipoMedioPago")
    private String tipoMedioPago;

    @XmlElement(name = "MedioPagoOtros")
    private String medioPagoOtros;

    @XmlElement(name = "TotalMedioPago")
    private BigDecimal totalMedioPago;

    public MedioPagoR() {}

    public MedioPagoR(Models.Resumen.MedioPagoR src) {
        if (src != null) {
            this.tipoMedioPago = src.getTipoMedioPago();
            this.medioPagoOtros = src.getMedioPagoOtros();
            this.totalMedioPago = src.getTotalMedioPago();
        }
    }
}
