package Models;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import lombok.Data;

/**
 * @author Al
 */

@Data
@Entity
public class AppSettings {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private int Id;
    private String nombrePerfil; //Nombre del Perfil
    @Lob private byte[] logo; //Logo de la empresa
    private String logoMimeType; //Practicamente la extencion de la imagen.
    private String correoElectronico; //Correo Electronico para enviar mensajes
    private String contrasenaCorreo; //Contrasena del Correo
    //Aqui deberian ir datos de tributacion...
    private String Identificacion;
    private String Nombre;
    private String primerApellido;
    private String segundoApellido;
    private String razonSocial;
    private String nombreNegocio;
    private String direccionCompleta;
    //
    private Boolean estatus; //Si se esta usando o no en el sistema
    private int completedSteps; //En que punto del setup esta...
    private int diferenciaCambio; //Cuanto hay que cambiar el tipo de cambio por las fluctuaciones diarias.
    
    
    
}
