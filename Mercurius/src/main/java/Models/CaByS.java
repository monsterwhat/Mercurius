package Models;

import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import java.util.List;
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

    @Column(length = 255)
    private String descripcion;

    @ElementCollection
    private List<String> categorias;

    private int impuesto;

    private String uri;

    private String estado;

    public Cabys() {
    }

    public Cabys(String codigo, String descripcion, List<String> categorias, int impuesto, String uri, String estado) {
        this.codigo = codigo;
        this.descripcion = descripcion;
        this.categorias = categorias;
        this.impuesto = impuesto;
        this.uri = uri;
        this.estado = estado;
    }
    
}

