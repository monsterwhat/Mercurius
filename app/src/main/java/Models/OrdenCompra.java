package Models;

import jakarta.annotation.Nullable;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.util.Date;
import java.util.List;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

@Entity
@Data
@Table(name = "orden_compra")
public class OrdenCompra {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false)
    private String numeroOrden;

    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    @Nullable
    @ManyToOne
    @JoinColumn(name = "proveedor_id", nullable = false)
    private Departamento proveedor;

    @Column(nullable = false)
    @Temporal(TemporalType.TIMESTAMP)
    private Date fechaOrden;

    @Nullable
    @Temporal(TemporalType.TIMESTAMP)
    private Date fechaEntregaEstimada;

    @Nullable
    @Temporal(TemporalType.TIMESTAMP)
    private Date fechaEntregaReal;

    @Column(nullable = false)
    private String estado; // BORRADOR, ENVIADA, CONFIRMADA, RECIBIDA, FACTURADA, CANCELADA

    @Nullable
    @Column(precision = 12, scale = 2)
    private BigDecimal totalEstimado;

    @Nullable
    @Column(precision = 12, scale = 2)
    private BigDecimal totalReal;

    @Nullable
    @Column(length = 500)
    private String notas;

    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    @Nullable
    @ManyToOne
    @JoinColumn(name = "usuario_id")
    private Users usuario;

    @Column(nullable = false)
    @Temporal(TemporalType.TIMESTAMP)
    private Date fecha;

    private boolean status; // true = activo, false = eliminado lógico

    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    @Nullable
    @OneToMany(mappedBy = "ordenCompra", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<OrdenCompraDetalle> detalles;

    public OrdenCompra() {
    }

    @PrePersist
    protected void onCreate() {
        fecha = new Date();
        if (fechaOrden == null) {
            fechaOrden = new Date();
        }
        if (estado == null) {
            estado = "BORRADOR";
        }
        if (totalEstimado == null) {
            totalEstimado = BigDecimal.ZERO;
        }
        if (totalReal == null) {
            totalReal = BigDecimal.ZERO;
        }
    }
}
