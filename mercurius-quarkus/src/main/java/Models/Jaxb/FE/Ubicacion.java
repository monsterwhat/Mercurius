package Models.Jaxb.FE;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import lombok.Data;

@XmlAccessorType(XmlAccessType.FIELD)
@Data
public class Ubicacion {
    @XmlElement(name = "Provincia")
    private String provincia;

    @XmlElement(name = "Canton")
    private String canton;

    @XmlElement(name = "Distrito")
    private String distrito;

    @XmlElement(name = "Barrio")
    private String barrio;

    @XmlElement(name = "OtrasSenas")
    private String otrasSenas;

    public Ubicacion() {}

    public Ubicacion(Models.Encabezado.Ubicacion src) {
        if (src != null) {
            this.provincia = src.getProvincia();
            this.canton = src.getCanton();
            this.distrito = src.getDistrito();
            this.barrio = src.getBarrio();
            this.otrasSenas = src.getOtrasSenas();
        }
    }
}
