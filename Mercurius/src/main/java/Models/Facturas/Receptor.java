package Models.Facturas;

import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import lombok.Data;

@Entity
@Data
public class Receptor {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY) // Use auto-increment strategy
    private Long id;
    private String nombre;
    private String identificacionTipo;
    private String identificacionNumero;
    private String nombreComercial;
    @Embedded private Ubicacion ubicacion;
    @Embedded private Telefono telefono;
    @Embedded private Fax fax;
    private String correoElectronico;

}
