package Models.Registros;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import lombok.Data;

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
    
    private String tipo;
    
    @Lob
    private String antes; //Usada para guardar alguna entidad que se modifico (vieja).
    
    @Lob
    private String despues; //la entidad con el cambio (nueva).
    
    private boolean vista; //Si ya se reviso o no...
    
}
