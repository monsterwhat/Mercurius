package Models.DTO;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * Full client profile: identity, Hacienda tax info, direccion/ubicacion (provincia/canton/distrito),
 * loyalty points state and flattened relations.
 * Relations are flattened to id + display-string: usuario -> (usuarioId, usuarioNombre),
 * actividades -> list of ActividadInfo (id + codigo + descripcion).
 * Credentials (password, refreshToken, tokenExpiry) and deprecated fields
 * (discount, CodigoActividadComercial) are intentionally excluded.
 */
public class ClientsDetailDTO {

    private int code; // Codigo (INT)
    private String name; // Nombre
    @Nullable private String address; // Direccion
    @Nullable private String provincia; // Provincia Hacienda code (e.g. "01" for San José)
    @Nullable private String canton; // Cantón Hacienda code (e.g. "01" for San José)
    @Nullable private String distrito; // Distrito Hacienda code (e.g. "01" for Carmen)
    @Nullable private String email; // Email
    @Nullable private Date birthDate; // Fecha Nacimiento
    @Nullable private String idType; // Tipo de Cedula
    @Nullable private String idNumber; // Cedula
    @Nullable private String phoneNumber; // Telefono
    private boolean taxpayer; // Tributario
    private int zoneCode; // Codigo de Zona
    @Nullable private String TipoIdentificacion; //Tipo de identificacion Fisica/Juridica/DiMEX/NITE
    @Nullable private Boolean status; //En caso de querer archivar o desabilitar
    @Nullable private BigDecimal puntosAcumulados; //Customer loyalty points
    @Nullable private Date lastPurchaseDate; //Date of last purchase for activity tracking
    @Nullable private String statusPuntos; //Status of points: 'active', 'inactive', 'expired'
    @Nullable private Long usuarioId; // Flattened: Clients.usuario.id
    @Nullable private String usuarioNombre; // Flattened: Clients.usuario.username
    private List<ActividadInfo> actividades = new ArrayList<>(); // Flattened: Códigos de actividad económica CIIU4 del cliente ante Hacienda

    public ClientsDetailDTO() {
    }

    public ClientsDetailDTO(int code, String name, @Nullable String address, @Nullable String provincia,
                            @Nullable String canton, @Nullable String distrito, @Nullable String email,
                            @Nullable Date birthDate, @Nullable String idType, @Nullable String idNumber,
                            @Nullable String phoneNumber, boolean taxpayer, int zoneCode,
                            @Nullable String tipoIdentificacion, @Nullable Boolean status,
                            @Nullable BigDecimal puntosAcumulados, @Nullable Date lastPurchaseDate,
                            @Nullable String statusPuntos, @Nullable Long usuarioId,
                            @Nullable String usuarioNombre, @Nullable List<ActividadInfo> actividades) {
        this.code = code;
        this.name = name;
        this.address = address;
        this.provincia = provincia;
        this.canton = canton;
        this.distrito = distrito;
        this.email = email;
        this.birthDate = birthDate;
        this.idType = idType;
        this.idNumber = idNumber;
        this.phoneNumber = phoneNumber;
        this.taxpayer = taxpayer;
        this.zoneCode = zoneCode;
        this.TipoIdentificacion = tipoIdentificacion;
        this.status = status;
        this.puntosAcumulados = puntosAcumulados;
        this.lastPurchaseDate = lastPurchaseDate;
        this.statusPuntos = statusPuntos;
        this.usuarioId = usuarioId;
        this.usuarioNombre = usuarioNombre;
        this.actividades = (actividades != null) ? actividades : new ArrayList<>();
    }

    public int getCode() { return code; }
    public void setCode(int code) { this.code = code; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    @Nullable
    public String getAddress() { return address; }
    public void setAddress(@Nullable String address) { this.address = address; }

    @Nullable
    public String getProvincia() { return provincia; }
    public void setProvincia(@Nullable String provincia) { this.provincia = provincia; }

    @Nullable
    public String getCanton() { return canton; }
    public void setCanton(@Nullable String canton) { this.canton = canton; }

    @Nullable
    public String getDistrito() { return distrito; }
    public void setDistrito(@Nullable String distrito) { this.distrito = distrito; }

    @Nullable
    public String getEmail() { return email; }
    public void setEmail(@Nullable String email) { this.email = email; }

    @Nullable
    public Date getBirthDate() { return birthDate; }
    public void setBirthDate(@Nullable Date birthDate) { this.birthDate = birthDate; }

    @Nullable
    public String getIdType() { return idType; }
    public void setIdType(@Nullable String idType) { this.idType = idType; }

    @Nullable
    public String getIdNumber() { return idNumber; }
    public void setIdNumber(@Nullable String idNumber) { this.idNumber = idNumber; }

    @Nullable
    public String getPhoneNumber() { return phoneNumber; }
    public void setPhoneNumber(@Nullable String phoneNumber) { this.phoneNumber = phoneNumber; }

    public boolean isTaxpayer() { return taxpayer; }
    public void setTaxpayer(boolean taxpayer) { this.taxpayer = taxpayer; }

    public int getZoneCode() { return zoneCode; }
    public void setZoneCode(int zoneCode) { this.zoneCode = zoneCode; }

    @Nullable
    public String getTipoIdentificacion() { return TipoIdentificacion; }
    public void setTipoIdentificacion(@Nullable String tipoIdentificacion) { this.TipoIdentificacion = tipoIdentificacion; }

    @Nullable
    public Boolean getStatus() { return status; }
    public void setStatus(@Nullable Boolean status) { this.status = status; }

    @Nullable
    public BigDecimal getPuntosAcumulados() { return puntosAcumulados; }
    public void setPuntosAcumulados(@Nullable BigDecimal puntosAcumulados) { this.puntosAcumulados = puntosAcumulados; }

    @Nullable
    public Date getLastPurchaseDate() { return lastPurchaseDate; }
    public void setLastPurchaseDate(@Nullable Date lastPurchaseDate) { this.lastPurchaseDate = lastPurchaseDate; }

    @Nullable
    public String getStatusPuntos() { return statusPuntos; }
    public void setStatusPuntos(@Nullable String statusPuntos) { this.statusPuntos = statusPuntos; }

    @Nullable
    public Long getUsuarioId() { return usuarioId; }
    public void setUsuarioId(@Nullable Long usuarioId) { this.usuarioId = usuarioId; }

    @Nullable
    public String getUsuarioNombre() { return usuarioNombre; }
    public void setUsuarioNombre(@Nullable String usuarioNombre) { this.usuarioNombre = usuarioNombre; }

    public List<ActividadInfo> getActividades() { return actividades; }
    public void setActividades(List<ActividadInfo> actividades) {
        this.actividades = (actividades != null) ? actividades : new ArrayList<>();
    }

    /**
     * Flattened actividad económica entry (from Models.ClienteActividad).
     */
    public static class ActividadInfo {

        private Long id;
        @Nonnull private String codigo; // Código CIIU4 de actividad económica (6 dígitos)
        @Nullable private String descripcion; // Descripción opcional de la actividad

        public ActividadInfo() {
        }

        public ActividadInfo(Long id, @Nonnull String codigo, @Nullable String descripcion) {
            this.id = id;
            this.codigo = codigo;
            this.descripcion = descripcion;
        }

        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }

        @Nonnull
        public String getCodigo() { return codigo; }
        public void setCodigo(@Nonnull String codigo) { this.codigo = codigo; }

        @Nullable
        public String getDescripcion() { return descripcion; }
        public void setDescripcion(@Nullable String descripcion) { this.descripcion = descripcion; }
    }
}
