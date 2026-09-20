package Models.Jaxb.FEE;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import java.math.BigDecimal;
import lombok.Data;

@XmlAccessorType(XmlAccessType.FIELD)
@Data
public class CodigoTipoMoneda {
    @XmlElement(name = "CodigoMoneda", required = true)
    private String codigoMoneda;

    @XmlElement(name = "TipoCambio", required = true)
    private BigDecimal tipoCambioMoneda = BigDecimal.ONE;

    public CodigoTipoMoneda() {}

    public CodigoTipoMoneda(Models.Resumen.CodigoTipoMoneda src) {
        if (src != null) {
            this.codigoMoneda = src.getCodigoMoneda();
            BigDecimal tc = src.getTipoCambioMoneda();
            if (tc == null) {
                tc = BigDecimal.ONE;
            }
            this.tipoCambioMoneda = tc;
        }
        if (this.tipoCambioMoneda == null) {
            this.tipoCambioMoneda = BigDecimal.ONE;
        }
    }
}
