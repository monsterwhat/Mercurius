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
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

/**
 * Actividad económica registrada de un cliente ante Hacienda.
 * Un cliente puede tener múltiples códigos de actividad (CIIU4).
 * Al emitir un comprobante electrónico, se selecciona uno de estos códigos
 * como CodigoActividadReceptor en el XML.
 */
@Entity
@Table(name = "ClienteActividad")
@Data
public class ClienteActividad {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "codigo", length = 6, nullable = false)
    private String codigo; // Código CIIU4 de actividad económica (6 dígitos)

    @Nullable @Column(name = "descripcion")
    private String descripcion; // Descripción opcional de la actividad

    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    @ManyToOne
    @JoinColumn(name = "cliente_code", nullable = false)
    private Clients cliente;

    public ClienteActividad() {
    }

    public ClienteActividad(String codigo, String descripcion, Clients cliente) {
        this.codigo = codigo;
        this.descripcion = descripcion;
        this.cliente = cliente;
    }
}
