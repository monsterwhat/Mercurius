package Models.Jaxb.NC;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import lombok.Data;

@XmlAccessorType(XmlAccessType.FIELD)
@Data
public class IdentificacionEmisor {
    @XmlElement(name = "Tipo")
    private String tipo;

    @XmlElement(name = "Numero")
    private String numero;

    public IdentificacionEmisor() {}

    public IdentificacionEmisor(Models.Encabezado.IdentificacionEmisor src) {
        if (src != null) {
            this.tipo = src.getTipo();
            this.numero = src.getNumero();
        }
    }
}
