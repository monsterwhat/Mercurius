package Models;

import Models.Users;
import jakarta.annotation.Nullable;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.util.Date;
import lombok.Data;

/**
 * Configuración global de márgenes de ganancia del sistema.
 *
 * Usa el patrón INSERT-only de ArticuloPrecio: cada guardado crea una NUEVA
 * fila en lugar de actualizar la existente. La configuración ACTUAL es siempre
 * la de mayor ID (ORDER BY id DESC LIMIT 1).
 *
 * Los ajustes de refrigeración son porcentuales (ej: 5.00 = +5%).
 */
@Data
@Entity
@Table(name = "configuracion_margen")
public class ConfiguracionMargen {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private int id;

    /** Margen base (%) que aplica a todos los productos sin refrigeración. */
    @Column(name = "margen_base", precision = 5, scale = 2, nullable = false)
    private BigDecimal margenBase;

    /** Ajuste adicional (%) para productos refrigerados (2-8°C). */
    @Column(name = "ajuste_refrigerado", precision = 5, scale = 2, nullable = false)
    private BigDecimal ajusteRefrigerado;

    /** Ajuste adicional (%) para productos congelados (-18°C). */
    @Column(name = "ajuste_congelado", precision = 5, scale = 2, nullable = false)
    private BigDecimal ajusteCongelado;

    /** Fecha de creación del registro. */
    @Column(name = "fecha_creacion", nullable = false)
    @Temporal(TemporalType.TIMESTAMP)
    private Date fechaCreacion;

    /** Usuario que realizó el cambio. */
    @Nullable
    @ManyToOne
    @JoinColumn(name = "usuario_id")
    private Users usuario;

    @PrePersist
    protected void onCreate() {
        fechaCreacion = new Date();
    }
}
