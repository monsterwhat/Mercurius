package Models;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
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
    
    @Nullable private String NombrePerfil; //Nombre del Perfil
    @Nullable @Lob private byte[] Logo; //Logo de la empresa
    @Nullable private String LogoMimeType; //Practicamente la extencion de la imagen.
    @Nullable private String CorreoElectronico; //Correo Electronico para enviar mensajes
    @Nullable private String ContrasenaCorreo; //Contrasena del Correo
    
    @Nullable private String Nombre; //Completo con apellidos
    @Nullable private String TipoIdentificacion; //Tipo de ID
    @Nullable private String Identificacion;
    @Nullable private String NombreNegocio; //NombreComercial
    @Nullable private String Provincia;
    @Nullable private String Canton;
    @Nullable private String Distrito;
    @Nullable private String Barrio;
    @Nullable private String DireccionCompleta; //OtrasSenas
    
    @Nullable private String CodigoPais;
    @Nullable private String Telefono;
    
    @Nullable private String CodigoPaisFax;
    @Nullable private String TelefonoFax;
    
    @Nullable private String correoElectronicoTributacion;
    
    @Nullable @Column(name = "correo_electronico_tributacion2", length = 200)
    private String correoElectronicoTributacion2;
    
    @Nullable @Column(name = "correo_electronico_tributacion3", length = 200)
    private String correoElectronicoTributacion3;
    
    @Nullable @Column(name = "correo_electronico_tributacion4", length = 200)
    private String correoElectronicoTributacion4;
    
    private String razonSocial;
    
    private String provedor;
    private String codigoActividad;
    
    //
    private Boolean estatus; //Si se esta usando o no en el sistema
    private int completedSteps; //En que punto del setup esta...
    
    @Nullable @Column(name = "cashbackPercentage")
    private BigDecimal cashbackPercentage; //Percentage of cashback for loyalty program

    private Integer ultimoConsecutivo; //Last consecutive number used for invoices
    
    @Nullable @Column(length = 3)
    private String codigoSucursal; //Branch code for Hacienda (e.g., "001")
    
    @Nullable @Column(length = 3)
    private String codigoTerminal; //Terminal code for Hacienda (e.g., "001")
    
    @Nullable @Column(length = 2)
    private String tipoDocumento; //Hacienda document type: "01"=FE, "04"=TE (default)
    
    @Nullable @Column(name = "puntosInactivityMonths")
    private Integer puntosInactivityMonths; //Months of inactivity before points expire
    
    // Hacienda Electronic Invoice Credentials
    @Lob
    private byte[] certificado; //.p12 certificate file
    
    private String certificadoPassword; //Certificate password (should be encrypted)
    
    private String haciendaApiKey; //Hacienda API token
    
    private String haciendaEnvironment; //"sandbox" or "production"
    
    private java.time.LocalDateTime haciendaTokenExpiry; //Token expiration for refresh

    // Notification settings for invoice rejection alerts
    private Boolean notificarRechazos; //Whether to send email notifications on rejection
    private String correoNotificaciones; //Email address to send rejection notifications to
    private Boolean notificarRechazosResumen; //Whether to send daily summary of rejected invoices

    // ============ BACKUP CONFIGURATION ============

    private Boolean backupHabilitado; //Enable/disable automatic database backup

    @Nullable @Column(length = 5)
    private String backupHora; //Hour to run backup, format "HH:mm" (e.g. "03:00")

    private Integer backupRetencionDias; //Days to keep backups (default 7)

    @Nullable @Column(length = 500)
    private String backupRuta; //Directory path for backup files

    private java.time.LocalDateTime backupUltimoEjecutado; //Timestamp of last successful backup

    // ============ DB-STORED CRYPTO & CONFIG (no env vars) ============

    @Nullable @Column(length = 64)
    private String haciendaEncryptionKey; //Auto-generated AES-256 key (Base64) for credential encryption

    @Nullable @Column(length = 500)
    private String haciendaCallbackUrl; //TRIBU-CR async notification callback

    // ============ FIDES E-INVOICING CONFIGURATION ============

    @Nullable @Column(length = 200)
    private String fidesApiUrl;

    @Nullable @Column(length = 200)
    private String fidesAuthEmail;

    @Nullable @Column(length = 500)
    private String fidesAuthPassword;

    @Nullable @Column(length = 100)
    private String fidesTenantId;

    @Nullable @Column(length = 100)
    private String fidesUserId;
}