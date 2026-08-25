package Models.DTO;

import jakarta.annotation.Nullable;
import java.util.Date;

/**
 * Read-side view of a stock alert (entity {@code Models.StockAlert}) for the
 * Alertas de Stock report page.
 *
 * Mapping notes (entity -> DTO):
 * - id                -> id
 * - articulo          -> flattened into articuloCodigo (the {@code Articulos}
 *                        PK {@code Long codigo}), articuloDescripcion,
 *                        articuloNombre, articuloCodigoBarra and
 *                        articuloStockOptimo (all displayed by the view)
 * - tipoAlerta        -> tipoAlerta ('low_stock', 'out_of_stock',
 *                        'reorder_suggestion')
 * - cantidadActual    -> cantidadActual
 * - cantidadMinima    -> cantidadMinima
 * - sugeridoReordenar -> sugeridoReordenar
 * - departamento      -> flattened into departamentoNombre
 * - estado            -> estado ('active', 'acknowledged', 'resolved')
 * - fechaCreacion     -> fechaCreacion
 * - fechaResolucion   -> fechaResolucion
 * - usuarioResolucion -> flattened into usuarioResolucionId +
 *                        usuarioResolucionUsername (null = unresolved alert)
 * - notas             -> notas
 */
public class StockAlertDTO {
    private int id;
    private Long articuloCodigo;
    private String articuloNombre;
    @Nullable private String articuloDescripcion;
    @Nullable private String articuloCodigoBarra;
    @Nullable private Integer articuloStockOptimo;
    private String tipoAlerta;
    @Nullable private Integer cantidadActual;
    @Nullable private Integer cantidadMinima;
    @Nullable private Integer sugeridoReordenar;
    @Nullable private String departamentoNombre;
    private String estado;
    private Date fechaCreacion;
    @Nullable private Date fechaResolucion;
    @Nullable private Long usuarioResolucionId;
    @Nullable private String usuarioResolucionUsername;
    @Nullable private String notas;

    public StockAlertDTO() {}

    public StockAlertDTO(int id, Long articuloCodigo, String articuloNombre,
                         @Nullable String articuloDescripcion, @Nullable String articuloCodigoBarra,
                         @Nullable Integer articuloStockOptimo, String tipoAlerta,
                         @Nullable Integer cantidadActual, @Nullable Integer cantidadMinima,
                         @Nullable Integer sugeridoReordenar, @Nullable String departamentoNombre,
                         String estado, Date fechaCreacion, @Nullable Date fechaResolucion,
                         @Nullable Long usuarioResolucionId, @Nullable String usuarioResolucionUsername,
                         @Nullable String notas) {
        this.id = id;
        this.articuloCodigo = articuloCodigo;
        this.articuloNombre = articuloNombre;
        this.articuloDescripcion = articuloDescripcion;
        this.articuloCodigoBarra = articuloCodigoBarra;
        this.articuloStockOptimo = articuloStockOptimo;
        this.tipoAlerta = tipoAlerta;
        this.cantidadActual = cantidadActual;
        this.cantidadMinima = cantidadMinima;
        this.sugeridoReordenar = sugeridoReordenar;
        this.departamentoNombre = departamentoNombre;
        this.estado = estado;
        this.fechaCreacion = fechaCreacion;
        this.fechaResolucion = fechaResolucion;
        this.usuarioResolucionId = usuarioResolucionId;
        this.usuarioResolucionUsername = usuarioResolucionUsername;
        this.notas = notas;
    }

    public int getId() { return id; }
    public void setId(int id) { this.id = id; }

    public Long getArticuloCodigo() { return articuloCodigo; }
    public void setArticuloCodigo(Long articuloCodigo) { this.articuloCodigo = articuloCodigo; }

    public String getArticuloNombre() { return articuloNombre; }
    public void setArticuloNombre(String articuloNombre) { this.articuloNombre = articuloNombre; }

    @Nullable
    public String getArticuloDescripcion() { return articuloDescripcion; }
    public void setArticuloDescripcion(@Nullable String articuloDescripcion) { this.articuloDescripcion = articuloDescripcion; }

    @Nullable
    public String getArticuloCodigoBarra() { return articuloCodigoBarra; }
    public void setArticuloCodigoBarra(@Nullable String articuloCodigoBarra) { this.articuloCodigoBarra = articuloCodigoBarra; }

    @Nullable
    public Integer getArticuloStockOptimo() { return articuloStockOptimo; }
    public void setArticuloStockOptimo(@Nullable Integer articuloStockOptimo) { this.articuloStockOptimo = articuloStockOptimo; }

    public String getTipoAlerta() { return tipoAlerta; }
    public void setTipoAlerta(String tipoAlerta) { this.tipoAlerta = tipoAlerta; }

    @Nullable
    public Integer getCantidadActual() { return cantidadActual; }
    public void setCantidadActual(@Nullable Integer cantidadActual) { this.cantidadActual = cantidadActual; }

    @Nullable
    public Integer getCantidadMinima() { return cantidadMinima; }
    public void setCantidadMinima(@Nullable Integer cantidadMinima) { this.cantidadMinima = cantidadMinima; }

    @Nullable
    public Integer getSugeridoReordenar() { return sugeridoReordenar; }
    public void setSugeridoReordenar(@Nullable Integer sugeridoReordenar) { this.sugeridoReordenar = sugeridoReordenar; }

    @Nullable
    public String getDepartamentoNombre() { return departamentoNombre; }
    public void setDepartamentoNombre(@Nullable String departamentoNombre) { this.departamentoNombre = departamentoNombre; }

    public String getEstado() { return estado; }
    public void setEstado(String estado) { this.estado = estado; }

    public Date getFechaCreacion() { return fechaCreacion; }
    public void setFechaCreacion(Date fechaCreacion) { this.fechaCreacion = fechaCreacion; }

    @Nullable
    public Date getFechaResolucion() { return fechaResolucion; }
    public void setFechaResolucion(@Nullable Date fechaResolucion) { this.fechaResolucion = fechaResolucion; }

    @Nullable
    public Long getUsuarioResolucionId() { return usuarioResolucionId; }
    public void setUsuarioResolucionId(@Nullable Long usuarioResolucionId) { this.usuarioResolucionId = usuarioResolucionId; }

    @Nullable
    public String getUsuarioResolucionUsername() { return usuarioResolucionUsername; }
    public void setUsuarioResolucionUsername(@Nullable String usuarioResolucionUsername) { this.usuarioResolucionUsername = usuarioResolucionUsername; }

    @Nullable
    public String getNotas() { return notas; }
    public void setNotas(@Nullable String notas) { this.notas = notas; }
}
