package Models.Comprobantes.Detalles;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import lombok.Data;

/**
 *
 * @author Al
 */
@Data
@Entity
@Table(name = "impuesto")
public class Impuesto {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "linea_detalle_id")
    private LineaDetalle lineaDetalle;

    @Column(name = "codigo", length = 2)
    private String codigo;

    @Column(name = "codigo_tarifa", length = 2)
    private String codigoTarifa;

    @Column(name = "tarifa", precision = 4, scale = 2)
    private BigDecimal tarifa;

    @Column(name = "factor_iva", precision = 5, scale = 4)
    private BigDecimal factorIVA;

    @Column(name = "monto", precision = 18, scale = 5)
    private BigDecimal monto;

    @Column(name = "monto_exportacion", precision = 18, scale = 5)
    private BigDecimal montoExportacion;

    @OneToOne(mappedBy = "impuesto", cascade = CascadeType.ALL, orphanRemoval = true)
    private Exoneracion exoneracion;

}
