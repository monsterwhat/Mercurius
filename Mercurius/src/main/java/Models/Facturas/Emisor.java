package Models.Facturas;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import lombok.Data;

@Entity
@Data
public class Emisor {
    @Id private Long id;
    private String nombre;
    private String identificacionTipo;
    private String identificacionNumero;
    private String nombreComercial;
    @Embedded private Ubicacion ubicacion;
    @Embedded private Telefono telefono;
    @Embedded private Telefono fax;
    private String correoElectronico;
    
}
