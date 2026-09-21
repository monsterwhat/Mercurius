package Models.Jaxb.REP;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlValue;
import lombok.Data;

@XmlAccessorType(XmlAccessType.FIELD)
@Data
public class CorreoElectronicoReceptor {
    @XmlValue
    private String correo;

    public CorreoElectronicoReceptor() {}

    public CorreoElectronicoReceptor(Models.Encabezado.CorreoElectronicoReceptor src) {
        if (src != null) {
            this.correo = src.getCorreo();
        }
    }
}
