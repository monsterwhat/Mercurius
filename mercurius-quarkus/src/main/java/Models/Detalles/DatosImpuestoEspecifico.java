package Models.Detalles;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlRootElement;
import java.math.BigDecimal;
import lombok.Data;

@XmlAccessorType(XmlAccessType.FIELD)
@XmlRootElement(name = "DatosImpuestoEspecifico")
@Data
@Entity
@Table(name = "datos_impuesto")
public class DatosImpuestoEspecifico {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
      
    @XmlElement(name = "NombreHijoXML")
    @Column(name = "cantidad_unidad_medida", precision = 7, scale = 2)
    private BigDecimal cantidadUnidadMedida;
    
    @XmlElement(name = "Porcentaje")
    @Column(name = "porcentaje", precision = 4, scale = 2)
    private BigDecimal porcentaje;
    
    @XmlElement(name = "Proporcion")
    @Column(name = "proporcion", precision = 5, scale = 2)
    private BigDecimal proporcion;
    
    @XmlElement(name = "VolumenUnidadConsumo")
    @Column(name = "volumen_unidad_consumo", precision = 7, scale = 2)
    private BigDecimal volumenUnidadConsumo;
    
    @XmlElement(name = "ImpuestoUnidad")
    @Column(name = "impuesto_unidad", precision = 18, scale = 2)
    private BigDecimal impuestoUnidad;
    
}
