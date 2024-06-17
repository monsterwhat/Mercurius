package Models.Comprobantes.Encabezado;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

/**
 *
 * @author Al
 */

@Data
@Entity
@Table(name = "identificacion_receptor")
public class IdentificacionReceptor {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tipo", length = 2)
    private String tipo;

    @Column(name = "numero", length = 12)
    private String numero;
}

