package Models.Jaxb.FEE;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import java.math.BigDecimal;
import lombok.Data;

@XmlAccessorType(XmlAccessType.FIELD)
@Data
public class DescuentoSurtido {
    @XmlElement(name = "MontoDescuentoSurtido")
    private BigDecimal montoDescuentoSurtido;

    @XmlElement(name = "CodigoDescuentoSurtido")
    private String codigoDescuentoSurtido;

    @XmlElement(name = "DescuentoSurtidoOtros")
    private String descuentoSurtidoOtros;

    public DescuentoSurtido() {}

    public DescuentoSurtido(Models.Detalles.DescuentoSurtido src) {
        if (src != null) {
            this.montoDescuentoSurtido = src.getMontoDescuentoSurtido();
            this.codigoDescuentoSurtido = src.getCodigoDescuentoSurtido();
            this.descuentoSurtidoOtros = src.getDescuentoSurtidoOtros();
        }
    }
}
