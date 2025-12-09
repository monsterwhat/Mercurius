package Models.ComprobantesV44.Resumen;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import java.math.BigDecimal;
import lombok.Data;

/**
 *
 * @author Al
 */

@XmlAccessorType(XmlAccessType.FIELD) 
@Embeddable
@Data
public class CodigoTipoMoneda {

    @XmlElement(name = "CodigoMoneda")
    @Column(name = "codigo_moneda", length = 3)
    private String codigoMoneda;

    @XmlElement(name = "TipoCambioMoneda")
    @Column(name = "tipo_cambio_moneda", precision = 18, scale = 5)
    private BigDecimal tipoCambioMoneda;

}
