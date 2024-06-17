package Models.Comprobantes.Referencias;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.Data;

/**
 *
 * @author Al
 */

@Data
@Entity
@Table(name = "informacion_referencia")
public class InformacionReferencia {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tipo_doc", length = 2)
    private String tipoDoc;

    @Column(name = "numero", length = 50)
    private String numero;

    @Column(name = "fecha_emision")
    private LocalDateTime fechaEmision;

    @Column(name = "codigo", length = 2)
    private String codigo;

    @Column(name = "razon", length = 180)
    private String razon;


}
