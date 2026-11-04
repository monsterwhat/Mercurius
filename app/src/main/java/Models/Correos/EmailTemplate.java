package Models.Correos;

import Models.Usuarios;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import java.util.Date;
import lombok.Data;

/**
 *
 * @author Al
 */
@Data
@Entity
public class EmailTemplate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String nombre;

    private String asunto;

    /**
     * Cuerpo HTML de la plantilla. La columna era VARCHAR(255), así que
     * cualquier plantilla real (con estilos) fallaba al guardar; con TEXT
     * cabe el HTML completo.
     */
    @jakarta.persistence.Column(columnDefinition = "TEXT")
    private String cuerpoHtml;

    private String tipo;

    private boolean status;

    private Date fechaCreacion;

    private Date fechaModificacion;

    @ManyToOne
    private Usuarios usuario;
}
