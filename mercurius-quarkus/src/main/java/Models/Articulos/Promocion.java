package Models.Articulos;

/**
 *
 * @author Al
 */ 
import Models.Articulos.Carrito.ArticuloCarrito;
import Models.Users;
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

    @Temporal(TemporalType.TIMESTAMP)
    private Date fechaInicio;

    @Temporal(TemporalType.TIMESTAMP)
    private Date fechaFin;

    private boolean activa;

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
        try {

            BigDecimal totalPromo = BigDecimal.ZERO;

            if (lista != null) {
                for (ArticuloCarrito articulo : lista) {
                    BigDecimal precioConUtilidad = articulo.getArticulo().getLastPrecio().getPrecioConUtilidad();

                    // Ensure that descuento is not null and handle cases where descuento is null or zero
                    BigDecimal porcentajeDescuento = (descuento != null ? descuento : BigDecimal.ZERO).divide(BigDecimal.valueOf(100));

                    // Apply discount
                    BigDecimal descuentoPromo = precioConUtilidad.multiply(porcentajeDescuento);
                    BigDecimal precioFinal = precioConUtilidad.subtract(descuentoPromo);

                    // Calculate IVA
                    BigDecimal porcentajeImpuesto = BigDecimal.valueOf(articulo.getArticulo().getCodigoCabys().getImpuesto()).divide(BigDecimal.valueOf(100));
                    BigDecimal iva = precioFinal.multiply(porcentajeImpuesto);

                    // Calculate final price including IVA
                    BigDecimal precioConUtilidadEIVA = precioFinal.add(iva);

                    // Multiply by the quantity of articles
                    totalPromo = totalPromo.add(precioConUtilidadEIVA.multiply(articulo.getCantidad()));
                }

                return totalPromo;

            }
            return BigDecimal.ZERO;  // Return zero when empty

        } catch (Exception e) {
            System.err.println("Error calculating totalPromo: " + e.getMessage());
            e.printStackTrace();
            return BigDecimal.ZERO;  // Return zero instead of null for consistency
        }
    }

}
