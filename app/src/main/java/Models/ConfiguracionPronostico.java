package Models;

import jakarta.annotation.Nullable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Temporal;
import jakarta.persistence.TemporalType;
import java.math.BigDecimal;
import java.util.Date;
import lombok.Data;

/**
 * Parametros economicos del pronostico y de la reposicion.
 *
 * <p>Siguen el mismo patron INSERT-only que {@link ConfiguracionMargen}: cada
 * guardado crea una fila nueva y la configuracion vigente es la de mayor id.
 * El historial queda auditable sin una tabla de auditoria aparte y un cambio
 * de costos no reescribe la decision que se tomo ayer.</p>
 *
 * <p>Estos valores no viven en {@code application.properties} porque son
 * decisiones del negocio (cuanto cuesta un faltante, cuanto tarda el
 * proveedor, cual es el minimo de compra), no del despliegue. La pantalla de
 * Ajustes los edita y el motor de pronostico los lee de aqui.</p>
 */
@Data
@Entity
@Table(name = "configuracion_pronostico")
public class ConfiguracionPronostico {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private int id;

    /** Cobertura objetivo del intervalo y del stock de seguridad, en (0, 1). */
    @Column(name = "nivel_servicio", precision = 4, scale = 3, nullable = false)
    private BigDecimal nivelServicio;

    /** Coste relativo de una unidad no vendida por falta de stock. */
    @Column(name = "costo_faltante", precision = 12, scale = 2, nullable = false)
    private BigDecimal costoFaltante;

    /** Coste relativo de mantener una unidad en inventario. */
    @Column(name = "costo_mantenimiento", precision = 12, scale = 2, nullable = false)
    private BigDecimal costoMantenimiento;

    /** Dias que tarda el proveedor en entregar. */
    @Column(name = "plazo_reposicion_dias", nullable = false)
    private int plazoReposicionDias;

    /** Cantidad minima que acepta el proveedor (0 = sin minimo). */
    @Column(name = "cantidad_minima_pedido", nullable = false)
    private int cantidadMinimaPedido;

    /** Multiplo de compra del proveedor (1 = sin restriccion). */
    @Column(name = "multiplo_pedido", nullable = false)
    private int multiploPedido;

    @Column(name = "fecha_creacion", nullable = false)
    @Temporal(TemporalType.TIMESTAMP)
    private Date fechaCreacion;

    @Nullable
    @ManyToOne
    @JoinColumn(name = "usuario_id")
    private Usuarios usuario;

    public static ConfiguracionPronostico defecto() {
        ConfiguracionPronostico config = new ConfiguracionPronostico();
        config.setNivelServicio(new BigDecimal("0.950"));
        config.setCostoFaltante(new BigDecimal("10.00"));
        config.setCostoMantenimiento(new BigDecimal("1.00"));
        config.setPlazoReposicionDias(7);
        config.setCantidadMinimaPedido(0);
        config.setMultiploPedido(1);
        return config;
    }

    @jakarta.persistence.PrePersist
    protected void onCreate() {
        if (fechaCreacion == null) {
            fechaCreacion = new Date();
        }
    }
}
