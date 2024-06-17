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
@Table(name = "ubicacion")
public class Ubicacion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "provincia", length = 1)
    private String provincia;

    @Column(name = "canton", length = 2)
    private String canton;

    @Column(name = "distrito", length = 2)
    private String distrito;

    @Column(name = "barrio", length = 2)
    private String barrio;

    @Column(name = "otras_senas", length = 250)
    private String otrasSenas;
}
