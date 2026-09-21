package Models.DTO;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import java.util.Date;

/**
 * Data Transfer Object for {@link Models.Departamento}.
 * Mirrors the entity's field types exactly; the usuario relation is flattened
 * to its id plus display string (username).
 */
public class DepartamentoDTO {

    private int id;

    @Nonnull
    private String nombre;

    @Nullable
    private String contactoNombre;

    @Nullable
    private String contactoTelefono;

    @Nullable
    private String contactoEmail;

    @Nullable
    private Integer plazoPagoDias;

    @Nullable
    private Integer tiempoEntregaDias;

    @Nullable
    private String notas;

    @Nullable
    private Boolean status; //En caso de querer archivar o desabilitar

    @Nullable
    private Long usuarioId;

    @Nullable
    private String usuarioUsername;

    @Nonnull
    private Date fecha;

    public DepartamentoDTO() {
    }

    public DepartamentoDTO(int id, @Nonnull String nombre,
                           @Nullable String contactoNombre, @Nullable String contactoTelefono,
                           @Nullable String contactoEmail, @Nullable Integer plazoPagoDias,
                           @Nullable Integer tiempoEntregaDias, @Nullable String notas,
                           @Nullable Boolean status, @Nullable Long usuarioId,
                           @Nullable String usuarioUsername, @Nonnull Date fecha) {
        this.id = id;
        this.nombre = nombre;
        this.contactoNombre = contactoNombre;
        this.contactoTelefono = contactoTelefono;
        this.contactoEmail = contactoEmail;
        this.plazoPagoDias = plazoPagoDias;
        this.tiempoEntregaDias = tiempoEntregaDias;
        this.notas = notas;
        this.status = status;
        this.usuarioId = usuarioId;
        this.usuarioUsername = usuarioUsername;
        this.fecha = fecha;
    }

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    @Nonnull
    public String getNombre() {
        return nombre;
    }

    public void setNombre(@Nonnull String nombre) {
        this.nombre = nombre;
    }

    @Nullable
    public String getContactoNombre() {
        return contactoNombre;
    }

    public void setContactoNombre(@Nullable String contactoNombre) {
        this.contactoNombre = contactoNombre;
    }

    @Nullable
    public String getContactoTelefono() {
        return contactoTelefono;
    }

    public void setContactoTelefono(@Nullable String contactoTelefono) {
        this.contactoTelefono = contactoTelefono;
    }

    @Nullable
    public String getContactoEmail() {
        return contactoEmail;
    }

    public void setContactoEmail(@Nullable String contactoEmail) {
        this.contactoEmail = contactoEmail;
    }

    @Nullable
    public Integer getPlazoPagoDias() {
        return plazoPagoDias;
    }

    public void setPlazoPagoDias(@Nullable Integer plazoPagoDias) {
        this.plazoPagoDias = plazoPagoDias;
    }

    @Nullable
    public Integer getTiempoEntregaDias() {
        return tiempoEntregaDias;
    }

    public void setTiempoEntregaDias(@Nullable Integer tiempoEntregaDias) {
        this.tiempoEntregaDias = tiempoEntregaDias;
    }

    @Nullable
    public String getNotas() {
        return notas;
    }

    public void setNotas(@Nullable String notas) {
        this.notas = notas;
    }

    @Nullable
    public Boolean getStatus() {
        return status;
    }

    public void setStatus(@Nullable Boolean status) {
        this.status = status;
    }

    @Nullable
    public Long getUsuarioId() {
        return usuarioId;
    }

    public void setUsuarioId(@Nullable Long usuarioId) {
        this.usuarioId = usuarioId;
    }

    @Nullable
    public String getUsuarioUsername() {
        return usuarioUsername;
    }

    public void setUsuarioUsername(@Nullable String usuarioUsername) {
        this.usuarioUsername = usuarioUsername;
    }

    @Nonnull
    public Date getFecha() {
        return fecha;
    }

    public void setFecha(@Nonnull Date fecha) {
        this.fecha = fecha;
    }
}
