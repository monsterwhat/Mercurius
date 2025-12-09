package Models.Correos;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import java.util.Date;
import java.util.List;
import lombok.Data;

/**
 *
 * @author Al
 */

@Data
@Entity
public class ReporteProgramado {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    private String perfil;
    
    private List<String> frecuencia; // Diario, Semanal, Quincenal, Mensual
    
    private List<String> reportes; // Tipos de Reportes...
    
    private List<String> correos; // Lista de Recipientes
    
    private Date lastRun;
    
    private boolean status;
    
    
    
}
