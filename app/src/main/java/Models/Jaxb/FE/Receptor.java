package Models.Jaxb.FE;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import java.util.List;
import java.util.stream.Collectors;
import lombok.Data;

@XmlAccessorType(XmlAccessType.FIELD)
@Data
public class Receptor {
    @XmlElement(name = "Nombre")
    private String nombre;

    @XmlElement(name = "Identificacion")
    private IdentificacionReceptor identificacion;

    @XmlElement(name = "NombreComercial")
    private String nombreComercial;

    @XmlElement(name = "Ubicacion")
    private Ubicacion ubicacion;

    @XmlElement(name = "Telefono")
    private Telefono telefono;

    @XmlElement(name = "OtrasSenasExtranjero")
    private String otrasSenasExtranjero;

    @XmlElement(name = "CorreoElectronico")
    private List<CorreoElectronicoReceptor> correosElectronicos;

    public Receptor() {}

    public Receptor(Models.Encabezado.Receptor src) {
        if (src != null) {
            this.nombre = src.getNombre();
            this.nombreComercial = src.getNombreComercial();
            this.otrasSenasExtranjero = src.getOtrasSenasExtranjero();
            if (src.getCorreosElectronicos() != null)
                this.correosElectronicos = src.getCorreosElectronicos().stream()
                    .map(CorreoElectronicoReceptor::new).collect(Collectors.toList());
            if (src.getIdentificacion() != null)
                this.identificacion = new IdentificacionReceptor(src.getIdentificacion());
            if (src.getUbicacion() != null)
                this.ubicacion = new Ubicacion(src.getUbicacion());
            if (src.getTelefono() != null)
                this.telefono = new Telefono(src.getTelefono());
        }
    }
}
