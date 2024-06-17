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
@Table(name = "descuento")
public class Descuento {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "linea_detalle_id")
    private LineaDetalle lineaDetalle;

    @Column(name = "monto_descuento", precision = 18, scale = 5)
    private BigDecimal montoDescuento;

    @Column(name = "naturaleza_descuento", length = 80)
    private String naturalezaDescuento;

}
