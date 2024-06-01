package Models;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
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
    private String directorioPrincipal; //Folder donde guardar archivos
    private String logo; //Logo de la empresa
    private String correoElectronico; //Correo Electronico para enviar mensajes
    private String contrasenaCorreo; //Contrasena del Correo
    //Aqui deberian ir datos de tributacion...
    private Boolean estatus; //Si se esta usando o no en el sistema
    
    
}
