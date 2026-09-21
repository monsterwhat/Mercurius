package Models.Jaxb.TE;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import java.math.BigDecimal;
import lombok.Data;

@XmlAccessorType(XmlAccessType.FIELD)
@Data
public class Descuento {
    @XmlElement(name = "MontoDescuento")
    private BigDecimal montoDescuento;

    @XmlElement(name = "CodigoDescuento")
    private String codigoDescuento;

    @XmlElement(name = "CodigoDescuentoOtro")
    private String codigoDescuentoOtro;

    @XmlElement(name = "NaturalezaDescuento")
    private String naturalezaDescuento;

    public Descuento() {}

    public Descuento(Models.Detalles.Descuento src) {
        if (src != null) {
            this.montoDescuento = src.getMontoDescuento();
            this.codigoDescuento = src.getCodigoDescuento();
            this.codigoDescuentoOtro = src.getCodigoDescuentoOtro();
            this.naturalezaDescuento = src.getNaturalezaDescuento();
        }
    }
}
