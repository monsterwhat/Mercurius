package Models;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Temporal;
import jakarta.persistence.TemporalType;
import java.io.Serializable;
import java.util.Date;
import lombok.Data;

/**
 *
 * @author Al
 */

@Entity
@Data
public class Inventario implements Serializable {
    
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private int codigo;
    
    @ManyToOne
    @JoinColumn(name = "articulo_codigo")
    private Articulos articulo;
        
    private int cantidad;
    
    private String tipoMovimiento;
    
    @Column(nullable = false, updatable = false)
    @Temporal(TemporalType.TIMESTAMP)
    private Date fechaMovimiento;
    
    private String notas;
    
    private Boolean status;

    public Inventario() {
    }

    public Inventario(int codigo, Articulos articulo, int cantidad, String tipoMovimiento, Date fechaMovimiento, String notas, Boolean status) {
        this.codigo = codigo;
        this.articulo = articulo;
        this.cantidad = cantidad;
        this.tipoMovimiento = tipoMovimiento;
        this.fechaMovimiento = fechaMovimiento;
        this.notas = notas;
        this.status = status;
    }
    
    
}
