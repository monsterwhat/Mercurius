package Models.Detalles;

import jakarta.annotation.Nullable;
import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlRootElement;
import java.util.List;

@XmlRootElement(name = "DetalleSurtido")
@XmlAccessorType(XmlAccessType.FIELD)
public class DetalleSurtido {
    @XmlElement(name = "LineaDetalleSurtido")
    @Nullable
    private List<LineaDetalleSurtido> lineasDetalleSurtido;
    
    public DetalleSurtido() {}
    
    public DetalleSurtido(List<LineaDetalleSurtido> lineas) {
        this.lineasDetalleSurtido = lineas;
    }
    
    public List<LineaDetalleSurtido> getLineasDetalleSurtido() {
        return lineasDetalleSurtido;
    }
}
