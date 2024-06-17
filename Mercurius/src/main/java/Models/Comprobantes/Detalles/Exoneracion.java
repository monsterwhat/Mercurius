package Models.Comprobantes.Detalles;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

/**
 *
 * @author Al
 */
@Data
@Entity
@Table(name = "exoneracion")
public class Exoneracion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "impuesto_id")
    private Impuesto impuesto;

    @Column(name = "tipo_documento", length = 2)
    private String tipoDocumento;

    @Column(name = "numero_documento", length = 40)
    private String numeroDocumento;

    @Column(name = "nombre_institucion", length = 160)
    private String nombreInstitucion;

    @Column(name = "fecha_emision")
    private LocalDateTime fechaEmision;

    @Column(name = "porcentaje_exoneracion", precision = 4, scale = 2)
    private BigDecimal porcentajeExoneracion;

    @Column(name = "monto_exoneracion", precision = 18, scale = 5)
    private BigDecimal montoExoneracion;

}
