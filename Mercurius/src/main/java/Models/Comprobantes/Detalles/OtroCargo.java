package Models.Comprobantes.Detalles;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import lombok.Data;

/**
 *
 * @author Al
 */
@Data
@Entity
@Table(name = "otro_cargo")
public class OtroCargo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "detalle_servicio_id")
    private DetalleServicio detalleServicio;

    @Column(name = "tipo_documento", length = 2)
    private String tipoDocumento;

    @Column(name = "numero_identidad_tercero", length = 12)
    private String numeroIdentidadTercero;

    @Column(name = "nombre_tercero", length = 100)
    private String nombreTercero;

    @Column(name = "detalle", length = 160)
    private String detalle;

    @Column(name = "porcentaje", precision = 6, scale = 5)
    private BigDecimal porcentaje;

    @Column(name = "monto_cargo", precision = 18, scale = 5)
    private BigDecimal montoCargo;

}
