package Models.Jaxb.REP;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import lombok.Data;

@XmlAccessorType(XmlAccessType.FIELD)
@Data
public class CodigoComercial {
    @XmlElement(name = "Tipo")
    private String tipo;

    @XmlElement(name = "Codigo")
    private String codigo;

    public CodigoComercial() {}

    public CodigoComercial(Models.Detalles.CodigoComercial src) {
        if (src != null) {
            this.tipo = src.getTipo();
            this.codigo = src.getCodigo();
        }
    }
}
