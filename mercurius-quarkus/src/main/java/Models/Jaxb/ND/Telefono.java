package Models.Jaxb.ND;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import lombok.Data;

@XmlAccessorType(XmlAccessType.FIELD)
@Data
public class Telefono {
    @XmlElement(name = "CodigoPais")
    private String codigoPais;

    @XmlElement(name = "NumTelefono")
    private String numeroTelefono;

    public Telefono() {}

    public Telefono(Models.Encabezado.Telefono src) {
        if (src != null) {
            this.codigoPais = src.getCodigoPais();
            this.numeroTelefono = src.getNumeroTelefono();
        }
    }
}
