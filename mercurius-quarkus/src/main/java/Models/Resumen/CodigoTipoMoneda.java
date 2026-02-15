package Models.Resumen;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import java.math.BigDecimal;
import lombok.Data;

@Embeddable
@Data
@XmlAccessorType(XmlAccessType.FIELD)
public class CodigoTipoMoneda {

    @XmlElement(name = "CodigoMoneda")
    @Column(name = "codigo_moneda", length = 3)
    private String codigoMoneda;

    @XmlElement(name = "TipoCambioMoneda")
    @Column(name = "tipo_cambio_moneda", precision = 18, scale = 5)
    private BigDecimal tipoCambioMoneda;

}
