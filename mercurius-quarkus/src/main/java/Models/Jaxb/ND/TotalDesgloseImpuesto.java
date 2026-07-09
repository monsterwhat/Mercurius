package Models.Jaxb.ND;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import java.math.BigDecimal;
import lombok.Data;

@XmlAccessorType(XmlAccessType.FIELD)
@Data
public class TotalDesgloseImpuesto {
    @XmlElement(name = "Codigo")
    private String codigo;

    @XmlElement(name = "CodigoTarifaIVA")
    private String codigoTarifaIVA;

    @XmlElement(name = "TotalMontoImpuesto")
    private BigDecimal totalMontoImpuesto;

    public TotalDesgloseImpuesto() {}

    public TotalDesgloseImpuesto(Models.Resumen.TotalDesgloseImpuesto src) {
        if (src != null) {
            this.codigo = src.getCodigo();
            this.codigoTarifaIVA = src.getCodigoTarifaIVA();
            this.totalMontoImpuesto = src.getTotalMontoImpuesto();
        }
    }
}
