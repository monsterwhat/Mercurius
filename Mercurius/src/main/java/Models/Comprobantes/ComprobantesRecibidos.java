package Models.Comprobantes;

import Models.Comprobantes.Detalles.DetalleServicio;
import Models.Comprobantes.Encabezado.Encabezado;
import Models.Comprobantes.Resumen.ResumenFactura;
import Models.Users;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import lombok.Data;

/**
 *
 * @author Al
 */

@Entity
@Data
public class ComprobantesRecibidos {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY) // Use auto-increment strategy
    private Long id;
    
    @OneToOne(fetch = FetchType.LAZY)
    private Encabezado encabezado;
    
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "detalle_servicio_id")
    private DetalleServicio detalles;
    
    @OneToOne(fetch = FetchType.LAZY)
    private ResumenFactura resumen;
    
    private Boolean status;
    private Boolean processed;
    
    @Column(nullable = false) // Ensures the field cannot be null in the database
    private Boolean paid = false; // Default value for the field
    
    private Users user;
    
}
