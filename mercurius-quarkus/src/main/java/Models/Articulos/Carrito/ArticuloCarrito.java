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

    public static Map<Integer, BigDecimal> calculateTotalTaxForUniqueRates(List<ArticuloCarrito> carrito) {
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