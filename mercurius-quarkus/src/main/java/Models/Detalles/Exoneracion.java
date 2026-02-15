package Models.Detalles;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlRootElement;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

@XmlAccessorType(XmlAccessType.FIELD)
@XmlRootElement(name = "Exoneracion")
@Data
@Entity
@Table(name = "exoneracion")
public class Exoneracion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @XmlElement(name = "Impuesto")
    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    @ManyToOne(cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @JoinColumn(name = "impuesto_id")
    private Impuesto impuesto;

    @XmlElement(name = "TipoDocumentoEX1")
    @Column(name = "tipo_documento_ex1", length = 2)
    private String tipoDocumentoEX1;
    
    @XmlElement(name = "TipoDocumentoOtro")
    @Column(name = "tipo_documento_otro", length = 100)
    private String tipoDocumentoOTRO;

    @XmlElement(name = "NumeroDocumento")
    @Column(name = "numero_documento", length = 40)
    private String numeroDocumento;
    
    @XmlElement(name = "Articulo")
    @Column(name = "articulo", length = 6)
    private BigDecimal articulo;
    
    @XmlElement(name = "Inciso")
    @Column(name = "inciso", length = 6)
    private BigDecimal inciso;

    @XmlElement(name = "NombreInstitucion")
    @Column(name = "nombre_institucion", length = 2)
    private String nombreInstitucion;
    
    @XmlElement(name = "NombreInstitucionOtros")
    @Column(name = "nombre_institucion_otros", length = 160)
    private String nombreInstitucionOtros;

    @XmlElement(name = "FechaEmisionEx")
    @Column(name = "fecha_emision_ex")
    private LocalDateTime fechaEmisionEX;

    @XmlElement(name = "TarifaExonerada")
    @Column(name = "tarifa_exonerada", precision = 4, scale = 2)
    private BigDecimal tarifaExonerada;

    @XmlElement(name = "MontoExoneracion")
    @Column(name = "monto_exoneracion", precision = 18, scale = 5)
    private BigDecimal montoExoneracion;

}
