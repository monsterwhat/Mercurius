package Models;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import lombok.Data;

/**
 *
 * @author Al
 */

@Entity
@Data
public class Cabys {

    @Id
    private String codigo;

    @Column(length = 2000)
    private String descripcion;

    @Column(length = 3000)
    private String categorias;

    private int impuesto;

    private String uri;

    private String estado;

    public Cabys() {
    }

    public Cabys(String codigo, String descripcion, String categorias, int impuesto, String uri, String estado) {
        this.codigo = codigo;
        this.descripcion = descripcion;
        this.categorias = categorias;
        this.impuesto = impuesto;
        this.uri = uri;
        this.estado = estado;
    }
    
}

