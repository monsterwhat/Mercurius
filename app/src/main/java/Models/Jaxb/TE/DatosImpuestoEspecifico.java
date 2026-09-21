package Models.Jaxb.TE;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import java.math.BigDecimal;
import lombok.Data;

@XmlAccessorType(XmlAccessType.FIELD)
@Data
public class DatosImpuestoEspecifico {
    @XmlElement(name = "NombreHijoXML")
    private BigDecimal cantidadUnidadMedida;

    @XmlElement(name = "Porcentaje")
    private BigDecimal porcentaje;

    @XmlElement(name = "Proporcion")
    private BigDecimal proporcion;

    @XmlElement(name = "VolumenUnidadConsumo")
    private BigDecimal volumenUnidadConsumo;

    @XmlElement(name = "ImpuestoUnidad")
    private BigDecimal impuestoUnidad;

    public DatosImpuestoEspecifico() {}

    public DatosImpuestoEspecifico(Models.Detalles.DatosImpuestoEspecifico src) {
        if (src != null) {
            this.cantidadUnidadMedida = src.getCantidadUnidadMedida();
            this.porcentaje = src.getPorcentaje();
            this.proporcion = src.getProporcion();
            this.volumenUnidadConsumo = src.getVolumenUnidadConsumo();
            this.impuestoUnidad = src.getImpuestoUnidad();
        }
    }
}
