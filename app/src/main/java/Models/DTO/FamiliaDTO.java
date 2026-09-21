package Models.DTO;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import java.util.Date;

/**
 * Data Transfer Object for {@link Models.Familia}.
 * Mirrors the entity's field types exactly; the usuario relation is flattened
 * to its id plus display string (username).
 */
public class FamiliaDTO {

    private int id;

    @Nonnull
    private String nombre;

    @Nullable
    private Boolean status; //En caso de querer archivar o desabilitar

    @Nonnull
    private Date fecha;

    @Nullable
    private Long usuarioId;

    @Nullable
    private String usuarioUsername;

    public FamiliaDTO() {
    }

    public FamiliaDTO(int id, @Nonnull String nombre, @Nullable Boolean status,
                      @Nonnull Date fecha, @Nullable Long usuarioId,
                      @Nullable String usuarioUsername) {
        this.id = id;
        this.nombre = nombre;
        this.status = status;
        this.fecha = fecha;
        this.usuarioId = usuarioId;
        this.usuarioUsername = usuarioUsername;
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
    public Boolean getStatus() {
        return status;
    }

    public void setStatus(@Nullable Boolean status) {
        this.status = status;
    }

    @Nonnull
    public Date getFecha() {
        return fecha;
    }

    public void setFecha(@Nonnull Date fecha) {
        this.fecha = fecha;
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
}
