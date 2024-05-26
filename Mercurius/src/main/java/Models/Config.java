package Models;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import lombok.Data;

/**
 *
 * @author Al
 */

@Entity
@Data
public class Config {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private int code;
    
    private String logo;
    
    
    
    
}
