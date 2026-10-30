package Models.Articulos.Carrito;

import Models.Articulos.Articulos;
import Models.Articulos.Promocion;
import Utils.CarritoCalculations;
import jakarta.annotation.Nullable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import lombok.Data;

//En Array representa el carrito con sus cantidades. 
@Data
@Entity
@Table(name = "articuloCarrito")
public class ArticuloCarrito {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(nullable = false, updatable = false)
    private Long codigo;

    @Column
    private BigDecimal cantidad;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "articulo_id", nullable = false)
    private Articulos articulo;

    @Column
    private BigDecimal descuento;

    @Column
    private BigDecimal precioPersonalizado;

    /**
     * Auditoria del ajuste manual de precio. Vive solo en memoria: el carrito
     * POS no se persiste, y el rastro real se copia a la LineaDetalle de la
     * factura al emitir (ComprobanteService.detallesComprobante).
     *
     * <p>Los precios de auditoria son FINALES (IVA incluido), que es lo que el
     * cajero lee en la etiqueta fisica. {@link #precioPersonalizado} sigue
     * siendo la base SIN IVA que consume la matematica fiscal existente.</p>
     */
    @Transient
    private BigDecimal precioOriginalSistema;

    @Transient
    private BigDecimal precioAjustadoFinal;

    @Transient
    private String motivoAjustePrecio;

    @Transient
    private String ajustePrecioPor;

    @Transient
    private Date ajustePrecioFecha;

    @Column
    private boolean isPromo;

    @Nullable
    @ManyToMany
    @JoinTable(
            name = "articulo_carrito_promocion",
            joinColumns = @JoinColumn(name = "articulo_carrito_id"),
            inverseJoinColumns = @JoinColumn(name = "promocion_id")
    )
    private List<Promocion> promociones = new ArrayList<>();

    public ArticuloCarrito() {
    }

    public BigDecimal getPrecioEfectivo() {
        if (precioPersonalizado != null) {
            return precioPersonalizado;
        }
        return articulo.getLastPrecio().getPrecioConUtilidad();
    }

    /**
     * Precio unitario FINAL (IVA incluido) que paga el cliente. Para una linea
     * ajustada devuelve exactamente el valor que el cajero escribio; para el
     * resto lo deriva de la base sin IVA con la tarifa del CABYS, igual que la
     * matematica de {@link CarritoCalculations}.
     */
    public BigDecimal getPrecioFinalEfectivo() {
        if (precioAjustadoFinal != null) {
            return precioAjustadoFinal;
        }
        return CarritoCalculations.getPrecioUnitarioFinal(this);
    }

    /**
     * Precio unitario FINAL del sistema antes de cualquier ajuste manual. Es el
     * valor que se guarda como {@code precioOriginalSistema} en la auditoria.
     */
    public BigDecimal getPrecioFinalSistema() {
        if (articulo != null) {
            var last = articulo.getLastPrecio();
            if (last != null && last.getPrecioFinal() != null) {
                return last.getPrecioFinal();
            }
            if (last != null) {
                return CarritoCalculations.getPrecioFinalDesdeBase(this, last.getPrecioConUtilidad());
            }
        }
        return CarritoCalculations.getPrecioUnitarioFinal(this);
    }

    /**
     * {@code true} cuando el cajero fijo un precio FINAL (IVA incluido) para
     * esta venta. En ese caso {@link #precioAjustadoFinal} es la fuente de
     * verdad de lo que se cobra y {@link #precioPersonalizado} es solo su base
     * sin IVA para la matematica fiscal: el impuesto se EXTRAE del precio final
     * en lugar de sumarse encima.
     */
    public boolean hasPrecioAjustado() {
        return precioAjustadoFinal != null && precioPersonalizado != null;
    }

    public ArticuloCarrito(BigDecimal cantidad, BigDecimal descuento, boolean isPromo, List<Promocion> promociones) {
        this.cantidad = cantidad;
        this.descuento = descuento;
        this.isPromo = isPromo;
        this.promociones = promociones;
    }

    public BigDecimal getTotalArticulo() {
        return CarritoCalculations.getTotalArticulo(this);
    }

    public BigDecimal getTotalArticulos() {
        return CarritoCalculations.getTotalArticulos(this);
    }

    public BigDecimal getArticuloConDescuento() {
        return CarritoCalculations.getArticuloConDescuento(this);
    }

    public BigDecimal getTotalDescuento() {
        return CarritoCalculations.getTotalDescuento(this);
    }

    public BigDecimal getTotalImpuesto() {
        return CarritoCalculations.getTotalImpuesto(this);
    }

    public BigDecimal calculateTotalCarrito(List<ArticuloCarrito> carrito) {
        return CarritoCalculations.calculateTotalCarrito(carrito);
    }

    public static Map<BigDecimal, BigDecimal> calculateTotalTaxForUniqueRates(List<ArticuloCarrito> carrito) {
        return CarritoCalculations.calculateTotalTaxByRate(carrito);
    }

    public List<Promocion> getPromocionesActivas() {
        List<Promocion> promocionesActivas = new ArrayList<>();
        if (this.promociones != null) {
            for (Promocion promocion : promociones) {
                if (promocion.isActiva()) {
                    Date hoy = new Date();
                    if (promocion.getFechaInicio().before(hoy) && promocion.getFechaFin().after(hoy)) {
                        promocionesActivas.add(promocion);
                    }
                }
            }

        }
        return promocionesActivas;
    }

}