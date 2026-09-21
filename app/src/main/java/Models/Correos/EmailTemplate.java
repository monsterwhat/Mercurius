package Models.Correos;

import Models.Users;
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

    private String cuerpoHtml;

    private String tipo;

    private boolean status;

    private Date fechaCreacion;

    private Date fechaModificacion;

    @ManyToOne
    private Users usuario;
}
