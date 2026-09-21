package Models.Jaxb.REP;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlValue;
import lombok.Data;

@XmlAccessorType(XmlAccessType.FIELD)
@Data
public class CorreoElectronicoEmisor {
    @XmlValue
    private String correo;

    public CorreoElectronicoEmisor() {}

    public CorreoElectronicoEmisor(Models.Encabezado.CorreoElectronicoEmisor src) {
        if (src != null) {
            this.correo = src.getCorreo();
        }
    }
}
