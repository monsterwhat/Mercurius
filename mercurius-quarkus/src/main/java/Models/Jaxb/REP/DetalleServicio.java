package Models.Jaxb.REP;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import java.util.List;
import java.util.stream.Collectors;
import lombok.Data;

@XmlAccessorType(XmlAccessType.FIELD)
@Data
public class DetalleServicio {
    @XmlElement(name = "LineaDetalle")
    private List<LineaDetalle> lineasDetalle;

    public DetalleServicio() {}

    public DetalleServicio(Models.Detalles.DetalleServicio src) {
        if (src != null && src.getLineasDetalle() != null)
            this.lineasDetalle = src.getLineasDetalle().stream()
                .map(LineaDetalle::new).collect(Collectors.toList());
    }
}
