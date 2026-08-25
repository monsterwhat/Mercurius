package Models.DTO;

import jakarta.annotation.Nullable;
import java.util.Date;

/**
 * Read-side view of one audit-log entry for the Log de Actividades page
 * ({@code Controllers.LogActividadController}).
 *
 * The controller has no dedicated log entity: it queries
 * {@code Models.Registros.Alertas} through
 * {@code Services.AlertasService.findFiltered(...)}, so this DTO mirrors the
 * columns that page actually displays.
 *
 * Mapping notes (entity -> DTO):
 * - timestamp -> fecha (as Date, mirroring the entity's getTimestampAsDate())
 * - tipo      -> tipo (column "Tipo")
 * - mensaje   -> mensaje (column "Mensaje")
 * - user      -> flattened into usuarioNombre (view renders null as "Sistema")
 * - source    -> origen (column "Origen")
 * - antes     -> valorAnterior (column "Valor Anterior", nullable)
 * - despues   -> valorNuevo (column "Valor Nuevo", nullable)
 * - vista     -> vista (column "Estado": Leído / No leído)
 */
public class LogActividadDTO {
    private int codigo;
    private Date fecha;
    private String tipo;
    private String mensaje;
    @Nullable private String usuarioNombre;
    private String origen;
    @Nullable private String valorAnterior;
    @Nullable private String valorNuevo;
    private boolean vista;

    public LogActividadDTO() {}

    public LogActividadDTO(int codigo, Date fecha, String tipo, String mensaje,
                           @Nullable String usuarioNombre, String origen,
                           @Nullable String valorAnterior, @Nullable String valorNuevo,
                           boolean vista) {
        this.codigo = codigo;
        this.fecha = fecha;
        this.tipo = tipo;
        this.mensaje = mensaje;
        this.usuarioNombre = usuarioNombre;
        this.origen = origen;
        this.valorAnterior = valorAnterior;
        this.valorNuevo = valorNuevo;
        this.vista = vista;
    }

    public int getCodigo() { return codigo; }
    public void setCodigo(int codigo) { this.codigo = codigo; }

    public Date getFecha() { return fecha; }
    public void setFecha(Date fecha) { this.fecha = fecha; }

    public String getTipo() { return tipo; }
    public void setTipo(String tipo) { this.tipo = tipo; }

    public String getMensaje() { return mensaje; }
    public void setMensaje(String mensaje) { this.mensaje = mensaje; }

    @Nullable
    public String getUsuarioNombre() { return usuarioNombre; }
    public void setUsuarioNombre(@Nullable String usuarioNombre) { this.usuarioNombre = usuarioNombre; }

    public String getOrigen() { return origen; }
    public void setOrigen(String origen) { this.origen = origen; }

    @Nullable
    public String getValorAnterior() { return valorAnterior; }
    public void setValorAnterior(@Nullable String valorAnterior) { this.valorAnterior = valorAnterior; }

    @Nullable
    public String getValorNuevo() { return valorNuevo; }
    public void setValorNuevo(@Nullable String valorNuevo) { this.valorNuevo = valorNuevo; }

    public boolean isVista() { return vista; }
    public void setVista(boolean vista) { this.vista = vista; }
}
