package Models.Facturas;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import java.util.List;
import lombok.Data;

@Entity
@Data
public class Receptor {
    @Id
    private Long id;

    private String nombre;
    private String identificacionTipo;
    private String identificacionNumero;
    private String nombreComercial;

    @OneToOne(cascade = CascadeType.ALL)
    private Ubicacion ubicacion;

    @Embedded
    private Telefono telefono;

    @OneToMany(mappedBy = "receptor", cascade = CascadeType.ALL)
    private List correosElectronicos;

}