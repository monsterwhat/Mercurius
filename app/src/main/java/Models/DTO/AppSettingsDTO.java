package Models.DTO;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Operational application settings for the views under META-INF/resources/secured/pages/Aplicacion/**
 * and META-INF/resources/secured/pages/Ajustes/**. Mirrors Models.AppSettings field by field,
 * EXCEPT the secret credentials, which are deliberately omitted per security policy:
 * contrasenaCorreo, certificado (.p12 keystore bytes), certificadoPassword, haciendaApiKey,
 * haciendaEncryptionKey and fidesAuthPassword. Consumers needing those must go through the
 * entity-bound controllers, never through this DTO.
 *
 * The nested {@link PrevalidationModeInfo} mirrors Models.Validacion.PrevalidationConfig
 * (the DB-stored pre-validation mode), which is not part of AppSettings itself.
 *
 * Note: stock alert thresholds have no global setting to expose; they are per-article values
 * (Articulos.diasStockSeguridad / estadoAlertas) computed by StockAlertService.
 */
public class AppSettingsDTO {

    private int id;

    @Nullable
    private String nombrePerfil; // Nombre del Perfil

    @Nullable
    private byte[] logo; // Logo de la empresa

    @Nullable
    private String logoMimeType; // Practicamente la extension de la imagen

    @Nullable
    private String correoElectronico; // Correo Electronico para enviar mensajes
    // contrasenaCorreo OMITTED: secret

    @Nullable
    private String nombre; // Completo con apellidos

    @Nullable
    private String tipoIdentificacion; // Tipo de ID

    @Nullable
    private String identificacion;

    @Nullable
    private String nombreNegocio; // NombreComercial

    @Nullable
    private String provincia;

    @Nullable
    private String canton;

    @Nullable
    private String distrito;

    @Nullable
    private String barrio;

    @Nullable
    private String direccionCompleta; // OtrasSenas

    @Nullable
    private String codigoPais;

    @Nullable
    private String telefono;

    @Nullable
    private String codigoPaisFax;

    @Nullable
    private String telefonoFax;

    @Nullable
    private String correoElectronicoTributacion;

    @Nullable
    private String correoElectronicoTributacion2;

    @Nullable
    private String correoElectronicoTributacion3;

    @Nullable
    private String correoElectronicoTributacion4;

    private String razonSocial;

    private String provedor;
    private String codigoActividad;

    private Boolean estatus; // Si se esta usando o no en el sistema
    private int completedSteps; // En que punto del setup esta

    @Nullable
    private BigDecimal cashbackPercentage; // Percentage of cashback for loyalty program

    private Integer ultimoConsecutivo; // Last consecutive number used for invoices

    @Nullable
    private String codigoSucursal; // Branch code for Hacienda (e.g., "001")

    @Nullable
    private String codigoTerminal; // Terminal code for Hacienda (e.g., "001")

    @Nullable
    private String tipoDocumento; // Hacienda document type: "01"=FE, "04"=TE (default)

    @Nullable
    private Integer puntosInactivityMonths; // Months of inactivity before points expire

    // certificado / certificadoPassword / haciendaApiKey OMITTED: secrets

    private String haciendaEnvironment; // "sandbox" or "production"

    private LocalDateTime haciendaTokenExpiry; // Token expiration for refresh

    // Notification settings for invoice rejection alerts
    private Boolean notificarRechazos; // Whether to send email notifications on rejection

    private String correoNotificaciones; // Email address to send rejection notifications to

    private Boolean notificarRechazosResumen; // Whether to send daily summary of rejected invoices

    // ============ BACKUP CONFIGURATION ============

    private Boolean backupHabilitado; // Enable/disable automatic database backup

    @Nullable
    private String backupHora; // Hour to run backup, format "HH:mm" (e.g. "03:00")

    private Integer backupRetencionDias; // Days to keep backups (default 7)

    @Nullable
    private String backupRuta; // Directory path for backup files

    private LocalDateTime backupUltimoEjecutado; // Timestamp of last successful backup

    // haciendaEncryptionKey OMITTED: secret

    @Nullable
    private String haciendaCallbackUrl; // TRIBU-CR async notification callback

    // ============ FIDES E-INVOICING CONFIGURATION ============

    private Boolean useFides; // true = Fides API, false/null = direct Hacienda

    @Nullable
    private String fidesApiUrl;

    @Nullable
    private String fidesAuthEmail;
    // fidesAuthPassword OMITTED: secret

    @Nullable
    private String fidesTenantId;

    @Nullable
    private String fidesUserId;

    public AppSettingsDTO() {
    }

    public AppSettingsDTO(int id, @Nullable String nombrePerfil, @Nullable byte[] logo,
                          @Nullable String logoMimeType, @Nullable String correoElectronico,
                          @Nullable String nombre, @Nullable String tipoIdentificacion,
                          @Nullable String identificacion, @Nullable String nombreNegocio,
                          @Nullable String provincia, @Nullable String canton,
                          @Nullable String distrito, @Nullable String barrio,
                          @Nullable String direccionCompleta, @Nullable String codigoPais,
                          @Nullable String telefono, @Nullable String codigoPaisFax,
                          @Nullable String telefonoFax, @Nullable String correoElectronicoTributacion,
                          @Nullable String correoElectronicoTributacion2,
                          @Nullable String correoElectronicoTributacion3,
                          @Nullable String correoElectronicoTributacion4,
                          @Nullable String razonSocial, @Nullable String provedor,
                          @Nullable String codigoActividad, @Nullable Boolean estatus,
                          int completedSteps, @Nullable BigDecimal cashbackPercentage,
                          @Nullable Integer ultimoConsecutivo, @Nullable String codigoSucursal,
                          @Nullable String codigoTerminal, @Nullable String tipoDocumento,
                          @Nullable Integer puntosInactivityMonths, @Nullable String haciendaEnvironment,
                          @Nullable LocalDateTime haciendaTokenExpiry, @Nullable Boolean notificarRechazos,
                          @Nullable String correoNotificaciones, @Nullable Boolean notificarRechazosResumen,
                          @Nullable Boolean backupHabilitado, @Nullable String backupHora,
                          @Nullable Integer backupRetencionDias, @Nullable String backupRuta,
                          @Nullable LocalDateTime backupUltimoEjecutado, @Nullable String haciendaCallbackUrl,
                          @Nullable Boolean useFides, @Nullable String fidesApiUrl,
                          @Nullable String fidesAuthEmail, @Nullable String fidesTenantId,
                          @Nullable String fidesUserId) {
        this.id = id;
        this.nombrePerfil = nombrePerfil;
        this.logo = logo;
        this.logoMimeType = logoMimeType;
        this.correoElectronico = correoElectronico;
        this.nombre = nombre;
        this.tipoIdentificacion = tipoIdentificacion;
        this.identificacion = identificacion;
        this.nombreNegocio = nombreNegocio;
        this.provincia = provincia;
        this.canton = canton;
        this.distrito = distrito;
        this.barrio = barrio;
        this.direccionCompleta = direccionCompleta;
        this.codigoPais = codigoPais;
        this.telefono = telefono;
        this.codigoPaisFax = codigoPaisFax;
        this.telefonoFax = telefonoFax;
        this.correoElectronicoTributacion = correoElectronicoTributacion;
        this.correoElectronicoTributacion2 = correoElectronicoTributacion2;
        this.correoElectronicoTributacion3 = correoElectronicoTributacion3;
        this.correoElectronicoTributacion4 = correoElectronicoTributacion4;
        this.razonSocial = razonSocial;
        this.provedor = provedor;
        this.codigoActividad = codigoActividad;
        this.estatus = estatus;
        this.completedSteps = completedSteps;
        this.cashbackPercentage = cashbackPercentage;
        this.ultimoConsecutivo = ultimoConsecutivo;
        this.codigoSucursal = codigoSucursal;
        this.codigoTerminal = codigoTerminal;
        this.tipoDocumento = tipoDocumento;
        this.puntosInactivityMonths = puntosInactivityMonths;
        this.haciendaEnvironment = haciendaEnvironment;
        this.haciendaTokenExpiry = haciendaTokenExpiry;
        this.notificarRechazos = notificarRechazos;
        this.correoNotificaciones = correoNotificaciones;
        this.notificarRechazosResumen = notificarRechazosResumen;
        this.backupHabilitado = backupHabilitado;
        this.backupHora = backupHora;
        this.backupRetencionDias = backupRetencionDias;
        this.backupRuta = backupRuta;
        this.backupUltimoEjecutado = backupUltimoEjecutado;
        this.haciendaCallbackUrl = haciendaCallbackUrl;
        this.useFides = useFides;
        this.fidesApiUrl = fidesApiUrl;
        this.fidesAuthEmail = fidesAuthEmail;
        this.fidesTenantId = fidesTenantId;
        this.fidesUserId = fidesUserId;
    }

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    @Nullable
    public String getNombrePerfil() {
        return nombrePerfil;
    }

    public void setNombrePerfil(@Nullable String nombrePerfil) {
        this.nombrePerfil = nombrePerfil;
    }

    @Nullable
    public byte[] getLogo() {
        return logo;
    }

    public void setLogo(@Nullable byte[] logo) {
        this.logo = logo;
    }

    @Nullable
    public String getLogoMimeType() {
        return logoMimeType;
    }

    public void setLogoMimeType(@Nullable String logoMimeType) {
        this.logoMimeType = logoMimeType;
    }

    @Nullable
    public String getCorreoElectronico() {
        return correoElectronico;
    }

    public void setCorreoElectronico(@Nullable String correoElectronico) {
        this.correoElectronico = correoElectronico;
    }

    @Nullable
    public String getNombre() {
        return nombre;
    }

    public void setNombre(@Nullable String nombre) {
        this.nombre = nombre;
    }

    @Nullable
    public String getTipoIdentificacion() {
        return tipoIdentificacion;
    }

    public void setTipoIdentificacion(@Nullable String tipoIdentificacion) {
        this.tipoIdentificacion = tipoIdentificacion;
    }

    @Nullable
    public String getIdentificacion() {
        return identificacion;
    }

    public void setIdentificacion(@Nullable String identificacion) {
        this.identificacion = identificacion;
    }

    @Nullable
    public String getNombreNegocio() {
        return nombreNegocio;
    }

    public void setNombreNegocio(@Nullable String nombreNegocio) {
        this.nombreNegocio = nombreNegocio;
    }

    @Nullable
    public String getProvincia() {
        return provincia;
    }

    public void setProvincia(@Nullable String provincia) {
        this.provincia = provincia;
    }

    @Nullable
    public String getCanton() {
        return canton;
    }

    public void setCanton(@Nullable String canton) {
        this.canton = canton;
    }

    @Nullable
    public String getDistrito() {
        return distrito;
    }

    public void setDistrito(@Nullable String distrito) {
        this.distrito = distrito;
    }

    @Nullable
    public String getBarrio() {
        return barrio;
    }

    public void setBarrio(@Nullable String barrio) {
        this.barrio = barrio;
    }

    @Nullable
    public String getDireccionCompleta() {
        return direccionCompleta;
    }

    public void setDireccionCompleta(@Nullable String direccionCompleta) {
        this.direccionCompleta = direccionCompleta;
    }

    @Nullable
    public String getCodigoPais() {
        return codigoPais;
    }

    public void setCodigoPais(@Nullable String codigoPais) {
        this.codigoPais = codigoPais;
    }

    @Nullable
    public String getTelefono() {
        return telefono;
    }

    public void setTelefono(@Nullable String telefono) {
        this.telefono = telefono;
    }

    @Nullable
    public String getCodigoPaisFax() {
        return codigoPaisFax;
    }

    public void setCodigoPaisFax(@Nullable String codigoPaisFax) {
        this.codigoPaisFax = codigoPaisFax;
    }

    @Nullable
    public String getTelefonoFax() {
        return telefonoFax;
    }

    public void setTelefonoFax(@Nullable String telefonoFax) {
        this.telefonoFax = telefonoFax;
    }

    @Nullable
    public String getCorreoElectronicoTributacion() {
        return correoElectronicoTributacion;
    }

    public void setCorreoElectronicoTributacion(@Nullable String correoElectronicoTributacion) {
        this.correoElectronicoTributacion = correoElectronicoTributacion;
    }

    @Nullable
    public String getCorreoElectronicoTributacion2() {
        return correoElectronicoTributacion2;
    }

    public void setCorreoElectronicoTributacion2(@Nullable String correoElectronicoTributacion2) {
        this.correoElectronicoTributacion2 = correoElectronicoTributacion2;
    }

    @Nullable
    public String getCorreoElectronicoTributacion3() {
        return correoElectronicoTributacion3;
    }

    public void setCorreoElectronicoTributacion3(@Nullable String correoElectronicoTributacion3) {
        this.correoElectronicoTributacion3 = correoElectronicoTributacion3;
    }

    @Nullable
    public String getCorreoElectronicoTributacion4() {
        return correoElectronicoTributacion4;
    }

    public void setCorreoElectronicoTributacion4(@Nullable String correoElectronicoTributacion4) {
        this.correoElectronicoTributacion4 = correoElectronicoTributacion4;
    }

    @Nullable
    public String getRazonSocial() {
        return razonSocial;
    }

    public void setRazonSocial(@Nullable String razonSocial) {
        this.razonSocial = razonSocial;
    }

    @Nullable
    public String getProvedor() {
        return provedor;
    }

    public void setProvedor(@Nullable String provedor) {
        this.provedor = provedor;
    }

    @Nullable
    public String getCodigoActividad() {
        return codigoActividad;
    }

    public void setCodigoActividad(@Nullable String codigoActividad) {
        this.codigoActividad = codigoActividad;
    }

    @Nullable
    public Boolean getEstatus() {
        return estatus;
    }

    public void setEstatus(@Nullable Boolean estatus) {
        this.estatus = estatus;
    }

    public int getCompletedSteps() {
        return completedSteps;
    }

    public void setCompletedSteps(int completedSteps) {
        this.completedSteps = completedSteps;
    }

    @Nullable
    public BigDecimal getCashbackPercentage() {
        return cashbackPercentage;
    }

    public void setCashbackPercentage(@Nullable BigDecimal cashbackPercentage) {
        this.cashbackPercentage = cashbackPercentage;
    }

    @Nullable
    public Integer getUltimoConsecutivo() {
        return ultimoConsecutivo;
    }

    public void setUltimoConsecutivo(@Nullable Integer ultimoConsecutivo) {
        this.ultimoConsecutivo = ultimoConsecutivo;
    }

    @Nullable
    public String getCodigoSucursal() {
        return codigoSucursal;
    }

    public void setCodigoSucursal(@Nullable String codigoSucursal) {
        this.codigoSucursal = codigoSucursal;
    }

    @Nullable
    public String getCodigoTerminal() {
        return codigoTerminal;
    }

    public void setCodigoTerminal(@Nullable String codigoTerminal) {
        this.codigoTerminal = codigoTerminal;
    }

    @Nullable
    public String getTipoDocumento() {
        return tipoDocumento;
    }

    public void setTipoDocumento(@Nullable String tipoDocumento) {
        this.tipoDocumento = tipoDocumento;
    }

    @Nullable
    public Integer getPuntosInactivityMonths() {
        return puntosInactivityMonths;
    }

    public void setPuntosInactivityMonths(@Nullable Integer puntosInactivityMonths) {
        this.puntosInactivityMonths = puntosInactivityMonths;
    }

    @Nullable
    public String getHaciendaEnvironment() {
        return haciendaEnvironment;
    }

    public void setHaciendaEnvironment(@Nullable String haciendaEnvironment) {
        this.haciendaEnvironment = haciendaEnvironment;
    }

    @Nullable
    public LocalDateTime getHaciendaTokenExpiry() {
        return haciendaTokenExpiry;
    }

    public void setHaciendaTokenExpiry(@Nullable LocalDateTime haciendaTokenExpiry) {
        this.haciendaTokenExpiry = haciendaTokenExpiry;
    }

    @Nullable
    public Boolean getNotificarRechazos() {
        return notificarRechazos;
    }

    public void setNotificarRechazos(@Nullable Boolean notificarRechazos) {
        this.notificarRechazos = notificarRechazos;
    }

    @Nullable
    public String getCorreoNotificaciones() {
        return correoNotificaciones;
    }

    public void setCorreoNotificaciones(@Nullable String correoNotificaciones) {
        this.correoNotificaciones = correoNotificaciones;
    }

    @Nullable
    public Boolean getNotificarRechazosResumen() {
        return notificarRechazosResumen;
    }

    public void setNotificarRechazosResumen(@Nullable Boolean notificarRechazosResumen) {
        this.notificarRechazosResumen = notificarRechazosResumen;
    }

    @Nullable
    public Boolean getBackupHabilitado() {
        return backupHabilitado;
    }

    public void setBackupHabilitado(@Nullable Boolean backupHabilitado) {
        this.backupHabilitado = backupHabilitado;
    }

    @Nullable
    public String getBackupHora() {
        return backupHora;
    }

    public void setBackupHora(@Nullable String backupHora) {
        this.backupHora = backupHora;
    }

    @Nullable
    public Integer getBackupRetencionDias() {
        return backupRetencionDias;
    }

    public void setBackupRetencionDias(@Nullable Integer backupRetencionDias) {
        this.backupRetencionDias = backupRetencionDias;
    }

    @Nullable
    public String getBackupRuta() {
        return backupRuta;
    }

    public void setBackupRuta(@Nullable String backupRuta) {
        this.backupRuta = backupRuta;
    }

    @Nullable
    public LocalDateTime getBackupUltimoEjecutado() {
        return backupUltimoEjecutado;
    }

    public void setBackupUltimoEjecutado(@Nullable LocalDateTime backupUltimoEjecutado) {
        this.backupUltimoEjecutado = backupUltimoEjecutado;
    }

    @Nullable
    public String getHaciendaCallbackUrl() {
        return haciendaCallbackUrl;
    }

    public void setHaciendaCallbackUrl(@Nullable String haciendaCallbackUrl) {
        this.haciendaCallbackUrl = haciendaCallbackUrl;
    }

    @Nullable
    public Boolean getUseFides() {
        return useFides;
    }

    public void setUseFides(@Nullable Boolean useFides) {
        this.useFides = useFides;
    }

    /**
     * Null-safe convenience mirroring Models.AppSettings.isUseFides().
     */
    public boolean isUseFides() {
        return useFides != null && useFides;
    }

    @Nullable
    public String getFidesApiUrl() {
        return fidesApiUrl;
    }

    public void setFidesApiUrl(@Nullable String fidesApiUrl) {
        this.fidesApiUrl = fidesApiUrl;
    }

    @Nullable
    public String getFidesAuthEmail() {
        return fidesAuthEmail;
    }

    public void setFidesAuthEmail(@Nullable String fidesAuthEmail) {
        this.fidesAuthEmail = fidesAuthEmail;
    }

    @Nullable
    public String getFidesTenantId() {
        return fidesTenantId;
    }

    public void setFidesTenantId(@Nullable String fidesTenantId) {
        this.fidesTenantId = fidesTenantId;
    }

    @Nullable
    public String getFidesUserId() {
        return fidesUserId;
    }

    public void setFidesUserId(@Nullable String fidesUserId) {
        this.fidesUserId = fidesUserId;
    }

    /**
     * Pre-validation mode settings mirrored from Models.Validacion.PrevalidationConfig
     * (table prevalidation_config). Only one config is active at a time.
     */
    public static class PrevalidationModeInfo {

        private long id;

        /** STRICT = missing CAByS codes are errors (reject invoice);
         *  LENIENT = missing CAByS codes are warnings (allow acceptance). */
        private boolean cabysStrictMode;

        /** Tolerance for tax calculation comparisons (e.g. 0.01 = ±1 colon). */
        @Nonnull
        private BigDecimal taxTolerance;

        /** Whether to warn on minor rounding differences within tolerance. */
        private boolean warnOnRounding;

        /** Max auto-correction attempts for emitted invoices rejected by Hacienda. */
        private int maxCorrectionAttempts;

        /** Whether this config is the active one (entity field isActive, column is_active). */
        private boolean active;

        /** Human-readable label for this config profile. */
        @Nonnull
        private String profileName;

        public PrevalidationModeInfo() {
            this.taxTolerance = new BigDecimal("0.01");
            this.maxCorrectionAttempts = 3;
            this.active = true;
            this.profileName = "default";
        }

        public PrevalidationModeInfo(long id, boolean cabysStrictMode, @Nonnull BigDecimal taxTolerance,
                                     boolean warnOnRounding, int maxCorrectionAttempts,
                                     boolean active, @Nonnull String profileName) {
            this.id = id;
            this.cabysStrictMode = cabysStrictMode;
            this.taxTolerance = taxTolerance;
            this.warnOnRounding = warnOnRounding;
            this.maxCorrectionAttempts = maxCorrectionAttempts;
            this.active = active;
            this.profileName = profileName;
        }

        public long getId() {
            return id;
        }

        public void setId(long id) {
            this.id = id;
        }

        public boolean isCabysStrictMode() {
            return cabysStrictMode;
        }

        public void setCabysStrictMode(boolean cabysStrictMode) {
            this.cabysStrictMode = cabysStrictMode;
        }

        @Nonnull
        public BigDecimal getTaxTolerance() {
            return taxTolerance;
        }

        public void setTaxTolerance(@Nonnull BigDecimal taxTolerance) {
            this.taxTolerance = taxTolerance;
        }

        public boolean isWarnOnRounding() {
            return warnOnRounding;
        }

        public void setWarnOnRounding(boolean warnOnRounding) {
            this.warnOnRounding = warnOnRounding;
        }

        public int getMaxCorrectionAttempts() {
            return maxCorrectionAttempts;
        }

        public void setMaxCorrectionAttempts(int maxCorrectionAttempts) {
            this.maxCorrectionAttempts = maxCorrectionAttempts;
        }

        public boolean isActive() {
            return active;
        }

        public void setActive(boolean active) {
            this.active = active;
        }

        @Nonnull
        public String getProfileName() {
            return profileName;
        }

        public void setProfileName(@Nonnull String profileName) {
            this.profileName = profileName;
        }
    }
}
