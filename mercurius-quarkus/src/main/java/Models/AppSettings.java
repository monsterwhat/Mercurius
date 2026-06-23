package Models;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.math.BigDecimal;
import lombok.Data;

@Data
@Entity
@Table(name = "appsettings",
        uniqueConstraints = @UniqueConstraint(columnNames = {"NombrePerfil"}))
public class AppSettings {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private int Id;
    
    private String NombrePerfil; //Nombre del Perfil
    @Lob private byte[] Logo; //Logo de la empresa
    private String LogoMimeType; //Practicamente la extencion de la imagen.
    private String CorreoElectronico; //Correo Electronico para enviar mensajes
    private String ContrasenaCorreo; //Contrasena del Correo
    
    private String Nombre; //Completo con apellidos
    private String TipoIdentificacion; //Tipo de ID
    private String Identificacion;
    private String NombreNegocio; //NombreComercial
    private String Provincia;
    private String Canton;
    private String Distrito;
    private String Barrio;
    private String DireccionCompleta; //OtrasSenas
    
    private String CodigoPais;
    private String Telefono;
    
    private String CodigoPaisFax;
    private String TelefonoFax;
    
    private String correoElectronicoTributacion;
    
    private String razonSocial;
    
    private String provedor;
    private String codigoActividad;
    
    //
    private Boolean estatus; //Si se esta usando o no en el sistema
    private int completedSteps; //En que punto del setup esta...
    
    @Column(name = "cashbackPercentage")
    private BigDecimal cashbackPercentage; //Percentage of cashback for loyalty program

    private Integer ultimoConsecutivo; //Last consecutive number used for invoices
    
    @Column(length = 3)
    private String codigoSucursal; //Branch code for Hacienda (e.g., "001")
    
    @Column(length = 3)
    private String codigoTerminal; //Terminal code for Hacienda (e.g., "001")
    
    @Column(name = "puntosInactivityMonths")
    private Integer puntosInactivityMonths; //Months of inactivity before points expire
    
    // Hacienda Electronic Invoice Credentials
    @Lob
    private byte[] certificado; //.p12 certificate file
    
    private String certificadoPassword; //Certificate password (should be encrypted)
    
    private String haciendaApiKey; //Hacienda API token
    
    private String haciendaEnvironment; //"sandbox" or "production"
    
    private java.time.LocalDateTime haciendaTokenExpiry; //Token expiration for refresh
    
}
