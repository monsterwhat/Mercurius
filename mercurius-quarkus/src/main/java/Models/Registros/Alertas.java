package Models.Registros;

import Models.Users;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

import jakarta.persistence.ManyToOne;
import jakarta.persistence.JoinColumn;
import lombok.Data;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;

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

    @Column(columnDefinition = "TEXT")
    private String mensaje;
    
    private String tipo; // This will be used as logLevel
    
    @Column(columnDefinition = "TEXT")
    private String antes; //Usada para guardar alguna entidad que se modifico (vieja).

    @Column(columnDefinition = "TEXT")
    private String despues; //la entidad con el cambio (nueva).

    private boolean vista; //Si ya se reviso o no...
    
    private LocalDateTime timestamp; // To record when the log entry was created
    
    @ManyToOne
@JoinColumn(name = "user_id")
private Users user;
    
    private String source; // To indicate the source of the log entry (e.g., the class or method that generated the log)
    
    public Date getTimestampAsDate() {
        if (timestamp == null) {
            return null;
        }
        return Date.from(timestamp.atZone(ZoneId.systemDefault()).toInstant());
    }
}
