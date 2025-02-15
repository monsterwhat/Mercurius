package Models.Registros;

import Models.Users;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import lombok.Data;
import java.time.LocalDateTime;

/**
 *
 * @author Al
 */

@Entity
@Data
public class Alertas {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private int codigo;

    private String mensaje;
    
    private String tipo; // This will be used as logLevel
    
    @Lob
    private String antes; //Usada para guardar alguna entidad que se modifico (vieja).
    
    @Lob
    private String despues; //la entidad con el cambio (nueva).
    
    private boolean vista; //Si ya se reviso o no...
    
    private LocalDateTime timestamp; // To record when the log entry was created
    
    private Users user;
    
    private String source; // To indicate the source of the log entry (e.g., the class or method that generated the log)
}
