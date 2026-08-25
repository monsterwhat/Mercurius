package Models.DTO;

import jakarta.annotation.Nullable;
import java.util.Date;

/**
 * Read-side view of an internal system alert (entity {@code Models.Registros.Alertas})
 * for the Registros Internos page.
 *
 * Mapping notes (entity -> DTO):
 * - timestamp -> fecha (as Date, mirroring the entity's getTimestampAsDate())
 * - tipo      -> titulo and nivel (the entity uses a single field as both
 *                the alert title and its log level/severity)
 * - mensaje   -> mensaje
 * - user      -> flattened into usuarioId + usuarioNombre (null = Sistema)
 * - source    -> modulo (class/method that generated the alert)
 */
public class AlertaDTO {
    private int codigo;
    private Date fecha;
    private String titulo;
    private String mensaje;
    @Nullable private Long usuarioId;
    @Nullable private String usuarioNombre;
    private String modulo;
    private String nivel;

    public AlertaDTO() {}

    public AlertaDTO(int codigo, Date fecha, String titulo, String mensaje,
                     @Nullable Long usuarioId, @Nullable String usuarioNombre,
                     String modulo, String nivel) {
        this.codigo = codigo;
        this.fecha = fecha;
        this.titulo = titulo;
        this.mensaje = mensaje;
        this.usuarioId = usuarioId;
        this.usuarioNombre = usuarioNombre;
        this.modulo = modulo;
        this.nivel = nivel;
    }

    public int getCodigo() { return codigo; }
    public void setCodigo(int codigo) { this.codigo = codigo; }

    public Date getFecha() { return fecha; }
    public void setFecha(Date fecha) { this.fecha = fecha; }

    public String getTitulo() { return titulo; }
    public void setTitulo(String titulo) { this.titulo = titulo; }

    public String getMensaje() { return mensaje; }
    public void setMensaje(String mensaje) { this.mensaje = mensaje; }

    @Nullable
    public Long getUsuarioId() { return usuarioId; }
    public void setUsuarioId(@Nullable Long usuarioId) { this.usuarioId = usuarioId; }

    @Nullable
    public String getUsuarioNombre() { return usuarioNombre; }
    public void setUsuarioNombre(@Nullable String usuarioNombre) { this.usuarioNombre = usuarioNombre; }

    public String getModulo() { return modulo; }
    public void setModulo(String modulo) { this.modulo = modulo; }

    public String getNivel() { return nivel; }
    public void setNivel(String nivel) { this.nivel = nivel; }
}
