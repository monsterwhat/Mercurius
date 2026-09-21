package Models.DTO;

import jakarta.annotation.Nullable;
import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

/**
 * Promo/combo view for API clients.
 * Mirrors the scalar fields of Models.Articulos.Promocion.
 * Relations are flattened: articulosCarrito -> pares {codigo, nombre},
 * usuario -> usuarioId + usuarioUsername.
 * Nested entities (ArticuloCarrito, Articulos, Users) are intentionally excluded.
 */
public class PromocionDTO {

    private int id; // Promocion.id
    private String nombre; // Promocion.nombre
    @Nullable private BigDecimal descuento; // Promocion.descuento (puede ser nulo si es combo)
    @Nullable private BigDecimal cantidad; // Promocion.cantidad (limitada por existencias)
    @Nullable private Date fechaInicio; // Promocion.fechaInicio
    @Nullable private Date fechaFin; // Promocion.fechaFin
    private boolean activa; // Promocion.activa
    private boolean ensambladoOrigen; // Promocion.ensambladoOrigen (DetalleSurtido Tipo 03 per Hacienda v4.4)
    private String codigoDescuento; // Promocion.codigoDescuento (default "06" DESCUENTO_PROMOCIONAL per Nota 20)

    // ---- Relaciones aplanadas ----
    @Nullable private Long usuarioId; // Promocion.usuario.id
    @Nullable private String usuarioUsername; // Promocion.usuario.username
    private List<ArticuloRef> articulosCarrito; // Promocion.articulosCarrito aplanado a pares id+nombre

    public PromocionDTO() {}

    public PromocionDTO(int id, String nombre, @Nullable BigDecimal descuento,
                        @Nullable BigDecimal cantidad, @Nullable Date fechaInicio,
                        @Nullable Date fechaFin, boolean activa, boolean ensambladoOrigen,
                        String codigoDescuento, @Nullable Long usuarioId,
                        @Nullable String usuarioUsername, List<ArticuloRef> articulosCarrito) {
        this.id = id;
        this.nombre = nombre;
        this.descuento = descuento;
        this.cantidad = cantidad;
        this.fechaInicio = fechaInicio;
        this.fechaFin = fechaFin;
        this.activa = activa;
        this.ensambladoOrigen = ensambladoOrigen;
        this.codigoDescuento = codigoDescuento;
        this.usuarioId = usuarioId;
        this.usuarioUsername = usuarioUsername;
        this.articulosCarrito = articulosCarrito;
    }

    public int getId() { return id; }
    public void setId(int id) { this.id = id; }

    public String getNombre() { return nombre; }
    public void setNombre(String nombre) { this.nombre = nombre; }

    @Nullable
    public BigDecimal getDescuento() { return descuento; }
    public void setDescuento(@Nullable BigDecimal descuento) { this.descuento = descuento; }

    @Nullable
    public BigDecimal getCantidad() { return cantidad; }
    public void setCantidad(@Nullable BigDecimal cantidad) { this.cantidad = cantidad; }

    @Nullable
    public Date getFechaInicio() { return fechaInicio; }
    public void setFechaInicio(@Nullable Date fechaInicio) { this.fechaInicio = fechaInicio; }

    @Nullable
    public Date getFechaFin() { return fechaFin; }
    public void setFechaFin(@Nullable Date fechaFin) { this.fechaFin = fechaFin; }

    public boolean isActiva() { return activa; }
    public void setActiva(boolean activa) { this.activa = activa; }

    public boolean isEnsambladoOrigen() { return ensambladoOrigen; }
    public void setEnsambladoOrigen(boolean ensambladoOrigen) { this.ensambladoOrigen = ensambladoOrigen; }

    public String getCodigoDescuento() { return codigoDescuento; }
    public void setCodigoDescuento(String codigoDescuento) { this.codigoDescuento = codigoDescuento; }

    @Nullable
    public Long getUsuarioId() { return usuarioId; }
    public void setUsuarioId(@Nullable Long usuarioId) { this.usuarioId = usuarioId; }

    @Nullable
    public String getUsuarioUsername() { return usuarioUsername; }
    public void setUsuarioUsername(@Nullable String usuarioUsername) { this.usuarioUsername = usuarioUsername; }

    public List<ArticuloRef> getArticulosCarrito() { return articulosCarrito; }
    public void setArticulosCarrito(List<ArticuloRef> articulosCarrito) { this.articulosCarrito = articulosCarrito; }

    /**
     * Flattened carrito entry: ArticuloCarrito.codigo + ArticuloCarrito.articulo.nombre.
     */
    public static class ArticuloRef {
        private Long codigo; // ArticuloCarrito.codigo
        @Nullable private String nombre; // ArticuloCarrito.articulo.nombre

        public ArticuloRef() {}

        public ArticuloRef(Long codigo, @Nullable String nombre) {
            this.codigo = codigo;
            this.nombre = nombre;
        }

        public Long getCodigo() { return codigo; }
        public void setCodigo(Long codigo) { this.codigo = codigo; }

        @Nullable
        public String getNombre() { return nombre; }
        public void setNombre(@Nullable String nombre) { this.nombre = nombre; }
    }
}
