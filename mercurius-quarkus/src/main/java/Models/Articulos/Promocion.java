package Models.Articulos;

import Models.Articulos.Carrito.ArticuloCarrito;
import Models.Users;
import Utils.CarritoCalculations;
import jakarta.annotation.Nullable;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

@EqualsAndHashCode(callSuper = false)
@Entity
@Data
public class Promocion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private int id;

    private String nombre;  // Nombre de la promoción

    private BigDecimal descuento; // Porcentaje o monto de descuento, puede ser nulo si es combo

    private BigDecimal cantidad; // En caso de que sea hasta agotar existencias(Limitada)

    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    @ManyToMany(mappedBy = "promociones", cascade = CascadeType.ALL)
    private List<ArticuloCarrito> articulosCarrito = new ArrayList<>();

    @Nullable
    @Temporal(TemporalType.TIMESTAMP)
    private Date fechaInicio;

    @Nullable
    @Temporal(TemporalType.TIMESTAMP)
    private Date fechaFin;

    private boolean activa;

    /**
     * Indicates whether this promo/combo was assembled at origin
     * (manufacturer/distributor/importer with own SKU/GTIN).
     * Per Hacienda v4.4 rules, only origin-assembled combos qualify
     * for DetalleSurtido (Tipo 03). In-store bundles must use
     * individual line items with discounts instead.
     */
    @Column(name = "ensamblado_origen")
    private boolean ensambladoOrigen = false;

    @Column(name = "codigo_descuento", length = 2)
    private String codigoDescuento = "06"; // Default: DESCUENTO_PROMOCIONAL per Nota 20

    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    @ManyToOne
    @JoinColumn(name = "usuario_id")
    private Users usuario; // Quien creó la promoción

    public List<Date> getFechas() {
        List<Date> fechas = new ArrayList<>();
        if (fechaInicio != null) {
            fechas.add(fechaInicio);
        }
        if (fechaFin != null) {
            fechas.add(fechaFin);
        }
        return fechas;
    }

    public BigDecimal getTotalPromo(List<ArticuloCarrito> lista, BigDecimal descuento) {
        return CarritoCalculations.calculateTotalPromo(lista, descuento);
    }

}