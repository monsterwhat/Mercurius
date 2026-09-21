package Models;

import jakarta.annotation.Nullable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import java.util.Objects;
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

    @Nullable
    @Column(length = 2000)
    private String descripcion;

    @Nullable
    @Column(length = 3000)
    private String categorias;

    @Column(length = 4)
    private String impuesto;

    private String uri;

    private String estado;

    public Cabys() {
    }

    public Cabys(String codigo, String descripcion, String categorias, String impuesto, String uri, String estado) {
        this.codigo = codigo;
        this.descripcion = descripcion;
        this.categorias = categorias;
        this.impuesto = impuesto;
        this.uri = uri;
        this.estado = estado;
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(codigo, descripcion);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        Cabys that = (Cabys) obj;
        return Objects.equals(codigo, that.codigo) &&
               Objects.equals(descripcion, that.descripcion);
    }
    
}
