package Models.DTO;

import jakarta.annotation.Nullable;
import java.util.Date;

/**
 * Data transfer object mirroring the {@code Models.Correos.EmailTemplate} entity
 * for the Correos/Templates administration pages.
 *
 * The {@code @ManyToOne usuario} relation is flattened to {@code usuarioId}
 * (same convention as {@code OrderDTO.invoiceId}) so no JPA entity graph —
 * and none of the user's sensitive fields — leaks into the DTO layer.
 */
public class EmailTemplateDTO {

    private Long id;
    private String nombre;
    private String asunto;
    @Nullable
    private String cuerpoHtml;
    private String tipo;
    private boolean status; // Activa / Inactiva
    @Nullable
    private Date fechaCreacion;
    @Nullable
    private Date fechaModificacion;
    @Nullable
    private Long usuarioId;

    public EmailTemplateDTO() {
    }

    public EmailTemplateDTO(Long id, String nombre, String asunto,
                            @Nullable String cuerpoHtml, String tipo, boolean status,
                            @Nullable Date fechaCreacion, @Nullable Date fechaModificacion,
                            @Nullable Long usuarioId) {
        this.id = id;
        this.nombre = nombre;
        this.asunto = asunto;
        this.cuerpoHtml = cuerpoHtml;
        this.tipo = tipo;
        this.status = status;
        this.fechaCreacion = fechaCreacion;
        this.fechaModificacion = fechaModificacion;
        this.usuarioId = usuarioId;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getNombre() {
        return nombre;
    }

    public void setNombre(String nombre) {
        this.nombre = nombre;
    }

    public String getAsunto() {
        return asunto;
    }

    public void setAsunto(String asunto) {
        this.asunto = asunto;
    }

    @Nullable
    public String getCuerpoHtml() {
        return cuerpoHtml;
    }

    public void setCuerpoHtml(@Nullable String cuerpoHtml) {
        this.cuerpoHtml = cuerpoHtml;
    }

    public String getTipo() {
        return tipo;
    }

    public void setTipo(String tipo) {
        this.tipo = tipo;
    }

    public boolean isStatus() {
        return status;
    }

    public void setStatus(boolean status) {
        this.status = status;
    }

    @Nullable
    public Date getFechaCreacion() {
        return fechaCreacion;
    }

    public void setFechaCreacion(@Nullable Date fechaCreacion) {
        this.fechaCreacion = fechaCreacion;
    }

    @Nullable
    public Date getFechaModificacion() {
        return fechaModificacion;
    }

    public void setFechaModificacion(@Nullable Date fechaModificacion) {
        this.fechaModificacion = fechaModificacion;
    }

    @Nullable
    public Long getUsuarioId() {
        return usuarioId;
    }

    public void setUsuarioId(@Nullable Long usuarioId) {
        this.usuarioId = usuarioId;
    }
}
