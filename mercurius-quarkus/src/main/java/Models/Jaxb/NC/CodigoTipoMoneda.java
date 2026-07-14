package Models.Jaxb.NC;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import java.math.BigDecimal;
import lombok.Data;

@XmlAccessorType(XmlAccessType.FIELD)
@Data
public class CodigoTipoMoneda {
    @XmlElement(name = "CodigoMoneda")
    private String codigoMoneda;

    @XmlElement(name = "TipoCambio")
    private BigDecimal tipoCambioMoneda;

    public CodigoTipoMoneda() {}

    public CodigoTipoMoneda(Models.Resumen.CodigoTipoMoneda src) {
        if (src != null) {
            this.codigoMoneda = src.getCodigoMoneda();
            this.tipoCambioMoneda = src.getTipoCambioMoneda();
        }
    }
}
