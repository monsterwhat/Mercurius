package Models.Comprobantes;

import Models.Comprobantes.Detalles.DetalleServicio;
import Models.Comprobantes.Encabezado.Encabezado;
import Models.Comprobantes.Resumen.ResumenFactura;
import Models.Users;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToOne;
import lombok.Data;

/**
 *
 * @author Al
 */

@Entity
@Data
public class ComprobanteFinal {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY) // Use auto-increment strategy
    private Long id;
    
    @OneToOne(fetch = FetchType.EAGER)
    private Encabezado encabezado;
    
    @OneToOne(fetch = FetchType.EAGER)
    private DetalleServicio detalles;
    
    @OneToOne(fetch = FetchType.EAGER)
    private ResumenFactura resumen;
    
    private Boolean status;
    private Boolean processed;
    private Users user;
    
}
