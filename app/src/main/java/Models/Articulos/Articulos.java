package Models.Articulos;

import Models.Cabys;
import Models.Departamento;
import Models.Enums.TipoRefrigeracion;
import Models.Familia;
import Models.Usuarios;
import jakarta.annotation.Nullable;
import jakarta.persistence.*;
import java.io.Serializable;
import java.math.BigDecimal; 
import java.util.Date;
import java.util.List; 
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

@Data
@Entity 
@Table(name = "articulos") 
public class Articulos implements Serializable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column (nullable = false, updatable = false)
    private Long codigo;

    @Column
    private String recomendacionCabys;

    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    @Nullable
    @ManyToOne
    @JoinColumn(name = "cabys_id")
    private Cabys codigoCabys;
    
    @Column
    private String nombre;

    @Column
    private String codigoBarra;

    @Nullable
    @Column(length = 500)
    private String descripcion;

    @Column
    private String UnidadMedida;

    @Column
    private String unidadMedidaComercial;

    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    @Nullable
    @ManyToOne
    @JoinColumn(name = "departamento_id")
    private Departamento departamento;

    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    @Nullable
    @ManyToOne
    @JoinColumn(name = "familia_id")
    private Familia familia;

    @Column
    private boolean status;

    @Column
    private boolean processed;

    // Stock management fields - automatically calculated by system
    @Nullable
    @Column(name = "stockOptimo")
    private Integer stockOptimo; // Calculated optimal stock level
    
    @Nullable
    @Column(name = "diasStockSeguridad")
    private Integer diasStockSeguridad; // Safety stock days (default: 7)
    
    @Nullable
    @Column(name = "estadoAlertas")
    private Boolean estadoAlertas = true; // Enable stock alerts

    @Column
    private boolean exento; // Whether the product is tax-exempt (exonerado)

    /**
     * Servicio (true) vs mercancía (false). Hacienda v4.4 separa los totales
     * del resumen en TotalServ* y TotalMercancias*; antes el resumen declaraba
     * todo como mercancía. Es una propiedad del artículo, no de la línea.
     */
    @Column(name = "es_servicio", nullable = false,
            columnDefinition = "boolean default false")
    private boolean esServicio;

    /**
     * Tipo de refrigeración del producto. Determina el ajuste de margen
     * de utilidad global aplicado (NINGUNA / REFRIGERADO / CONGELADO).
     */
    @Nullable
    @Enumerated(EnumType.STRING)
    @Column(name = "tipo_refrigeracion", length = 20)
    private TipoRefrigeracion tipoRefrigeracion;

    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    @Nullable
    @OneToMany(mappedBy = "articulo", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    // El historico se lee por id ascendente: el ultimo elemento es SIEMPRE la
    // fila de precio mas reciente (getLastPrecio). Sin este orden la coleccion
    // EAGER llegaba en el orden que devolviera la base y el POS podia cobrar
    // un precio viejo.
    @OrderBy("id ASC")
    private List<ArticuloPrecio> precios; // List of pricing details

    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    @Nullable
    @OneToMany(mappedBy = "articulo", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("orden ASC")
    private List<ArticuloImagen> imagenes;
   
    @Column(nullable = false)
    @Temporal(TemporalType.TIMESTAMP)
    private Date fecha; //LOGS LOGS LOGS!!

    /**
     * Bloqueo optimista para ediciones concurrentes de la ficha del artículo.
     */
    @Version
    @EqualsAndHashCode.Exclude
    private Long version = 0L;

    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    @Nullable
    @ManyToOne
    @JoinColumn(name = "usuario_id")
    private Usuarios usuario; //Referencia a quien creo el Articulo

    @PrePersist
    protected void onCreate() {
        fecha = new Date(); // Sets the current timestamp when creating the entity
    }

    /**
     * Precio vigente del artículo: la fila con la {@code fechaCompra} MÁS
     * RECIENTE (a igualdad de fecha, el id mayor).
     *
     * <p>Antes se elegía solo por id: una fila cargada después pero con fecha de
     * compra anterior ganaba como "último precio" y el POS cobraba un precio
     * viejo. La fecha del cambio es el dato real del historial; el id solo
     * desempata. Las filas sin fecha cuentan como las más antiguas.</p>
     */
    public ArticuloPrecio getLastPrecio() {
        if (precios == null || precios.isEmpty()) {
            return null;
        }
        ArticuloPrecio last = null;
        for (ArticuloPrecio precio : precios) {
            if (precio == null) {
                continue;
            }
            if (last == null || esMasNuevo(precio, last)) {
                last = precio;
            }
        }
        return last;
    }

    /** true cuando {@code a} es más reciente que {@code b} (fecha, luego id). */
    private static boolean esMasNuevo(ArticuloPrecio a, ArticuloPrecio b) {
        java.util.Date fa = a.getFechaCompra();
        java.util.Date fb = b.getFechaCompra();
        if (fa != null && fb != null) {
            int porFecha = fa.compareTo(fb);
            if (porFecha != 0) {
                return porFecha > 0;
            }
            return a.getId() >= b.getId();
        }
        if (fa != null) {
            return true;
        }
        if (fb != null) {
            return false;
        }
        return a.getId() >= b.getId();
    }

    public BigDecimal getLastPrecioArticulo() {
        ArticuloPrecio lastPrecio = getLastPrecio();
        return lastPrecio != null ? lastPrecio.getPrecioFinal() : null;
    }

    public BigDecimal getPrecioFinalBetweenDates(Date startDate, Date endDate) {
        // Check if there are precios available
        if (precios != null && !precios.isEmpty()) {
            for (ArticuloPrecio precio : precios) {
                // Ensure the precio has a date to compare
                if (precio.getFechaCompra() != null) {
                    Date precioFecha = precio.getFechaCompra();

                    // Check if the date falls within the startDate and endDate (inclusive)
                    if (!precioFecha.before(startDate) && !precioFecha.after(endDate)) {
                        return precio.getPrecioFinal();
                    }
                }
            }
        }

        return BigDecimal.ZERO; // Return 0 if no precio matches the date range
    }

}