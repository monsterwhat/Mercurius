package Models.Jaxb.TE;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import lombok.Data;

@XmlAccessorType(XmlAccessType.FIELD)
@Data
public class NumeroVINoSerie {
    @XmlElement(name = "NumeroVINoSerie")
    private String numeroVINoSerie;

    public NumeroVINoSerie() {}

    public NumeroVINoSerie(Models.Detalles.NumeroVINoSerie src) {
        if (src != null) {
            this.numeroVINoSerie = src.getNumeroVINoSerie();
        }
    }
}
