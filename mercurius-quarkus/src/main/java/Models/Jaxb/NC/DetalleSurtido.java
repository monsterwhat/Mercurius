package Models.Jaxb.NC;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import java.util.List;
import java.util.stream.Collectors;
import lombok.Data;

@XmlAccessorType(XmlAccessType.FIELD)
@Data
public class DetalleSurtido {
    @XmlElement(name = "LineaDetalleSurtido")
    private List<LineaDetalleSurtido> lineasDetalleSurtido;

    public DetalleSurtido() {}

    public DetalleSurtido(Models.Detalles.DetalleSurtido src) {
        if (src != null && src.getLineasDetalleSurtido() != null)
            this.lineasDetalleSurtido = src.getLineasDetalleSurtido().stream()
                .map(LineaDetalleSurtido::new).collect(Collectors.toList());
    }
}
