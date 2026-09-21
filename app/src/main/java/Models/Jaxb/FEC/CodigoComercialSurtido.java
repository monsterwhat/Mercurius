package Models.Jaxb.FEC;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import lombok.Data;

@XmlAccessorType(XmlAccessType.FIELD)
@Data
public class CodigoComercialSurtido {
    @XmlElement(name = "TipoSurtido")
    private String tipoSurtido;

    @XmlElement(name = "CodigoSurtido")
    private String codigoSurtido;

    public CodigoComercialSurtido() {}

    public CodigoComercialSurtido(Models.Detalles.CodigoComercialSurtido src) {
        if (src != null) {
            this.tipoSurtido = src.getTipoSurtido();
            this.codigoSurtido = src.getCodigoSurtido();
        }
    }
}
