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
@Table(name = "fax")
public class Fax {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "codigo_pais", length = 3)
    private String codigoPais;

    @Column(name = "numero_fax", length = 20)
    private String numeroFax;
}
