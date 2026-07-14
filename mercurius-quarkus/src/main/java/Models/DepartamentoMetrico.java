package Models;

import jakarta.annotation.Nullable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Temporal;
import jakarta.persistence.TemporalType;
import java.math.BigDecimal;
import java.util.Date;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

/**
 * Almacena las métricas calculadas de rendimiento para cada Departamento (Proveedor).
 * Se recalcula periódicamente o bajo demanda.
 */
@Entity
@Data
public class DepartamentoMetrico {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    @Nullable
    @ManyToOne
    @JoinColumn(name = "departamento_id")
    private Departamento departamento;

    @Column(nullable = false)
    @Temporal(TemporalType.TIMESTAMP)
    private Date fechaCalculo;

    /** Total de facturas recibidas de este proveedor */
    @Column(nullable = false)
    private int totalFacturasRecibidas;

    /** Facturas que ya fueron pagadas */
    @Column(nullable = false)
    private int facturasPagadas;

    /** Monto total de compras (suma de totalComprobante) */
    @Column(nullable = false, precision = 18, scale = 5)
    private BigDecimal montoTotalCompras;

    /** Monto promedio por factura */
    @Column(nullable = false, precision = 18, scale = 5)
    private BigDecimal montoPromedioFactura;

    /** Tiempo promedio de entrega en días (calculado desde fechas de factura) */
    @Column(nullable = false)
    private double tiempoEntregaPromedio;

    /** Porcentaje de entregas a tiempo (0-100) */
    @Column(nullable = false)
    private double tasaOnTimeDelivery;

    /** Cantidad de artículos distintos comprados a este proveedor */
    @Column(nullable = false)
    private int articulosComprados;

    /** Score de rendimiento calculado (0-100) */
    @Column(nullable = false)
    private double score;

    @PrePersist
    protected void onCreate() {
        if (fechaCalculo == null) {
            fechaCalculo = new Date();
        }
        if (montoTotalCompras == null) {
            montoTotalCompras = BigDecimal.ZERO;
        }
        if (montoPromedioFactura == null) {
            montoPromedioFactura = BigDecimal.ZERO;
        }
    }
}
