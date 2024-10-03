package Models;

/**
 *
 * @author Al
 */

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import lombok.Data;

@Entity
@Data
public class Promocion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private int id;

    private String nombre;  // Nombre de la promoción
    
    private String tipoPromocion;  // "DESCUENTO" o "COMBO"
    
    private BigDecimal descuento; // Porcentaje o monto de descuento, puede ser nulo si es combo
    
    private BigDecimal precioCombo; // Precio final si es un combo
    
    @ManyToMany
    @JoinTable(
        name = "articulo_promocion", // Join table name
        joinColumns = @JoinColumn(name = "promocion_id"), // Foreign key for Promocion
        inverseJoinColumns = @JoinColumn(name = "articulo_id") // Foreign key for Articulo
    )
    private List<Articulos> articulos; // List of articles in the promotion
    
    @ElementCollection
    @CollectionTable(name = "promocion_cantidades", joinColumns = @JoinColumn(name = "promocion_id"))
    @Column(name = "cantidad")
    private List<BigDecimal> cantidades; // List of quantities for each article
    
    @Temporal(TemporalType.TIMESTAMP)
    private Date fechaInicio;

    @Temporal(TemporalType.TIMESTAMP)
    private Date fechaFin;
    
    private boolean activa;
    
    @ManyToOne
    @JoinColumn(name = "usuario_id")
    private Users usuario; // Quien creó la promoción
    
    // Validaciones de promociones
    public boolean isDescuentoValid() {
        return tipoPromocion.equals("DESCUENTO") && descuento != null;
    }

    public boolean isComboValid() {
        return tipoPromocion.equals("COMBO") && precioCombo != null;
    }

    // Método para aplicar un descuento
    public BigDecimal aplicarDescuento(ArticuloPrecio precioArticulo) {
        if (isDescuentoValid()) {
            BigDecimal descuentoAplicado = precioArticulo.getPrecioFinal()
                .multiply(BigDecimal.ONE.subtract(descuento.divide(BigDecimal.valueOf(100))));
            return descuentoAplicado;
        }
        return precioArticulo.getPrecioFinal(); // No aplicar descuento si no es válido
    }

    // Método para calcular el precio del combo
    public BigDecimal calcularPrecioCombo() {
        if (isComboValid()) {
            return precioCombo;
        }
        return BigDecimal.ZERO; // O lanza una excepción si el combo no es válido
    }
    
    public BigDecimal getCantidad(Articulos articulo) {
        int index = articulos.indexOf(articulo);
        if (index != -1 && index < cantidades.size()) {
            return cantidades.get(index);
        }
        return BigDecimal.ZERO; // Or handle this case as needed
    }


    public List<ArticuloCarrito> getArticulosCarrito() {
    List<ArticuloCarrito> articulosCarrito = new ArrayList<>();

        for (int i = 0; i < articulos.size(); i++) {
            Articulos articulo = articulos.get(i);
            Double cantidad = cantidades.get(i).doubleValue();

            // Create a new ArticuloCarrito for each iteration
            ArticuloCarrito articuloCarrito = new ArticuloCarrito();
            articuloCarrito.setArticulo(articulo);
            articuloCarrito.setCantidad(cantidad);

            // Add the new ArticuloCarrito to the list
            articulosCarrito.add(articuloCarrito);
        }

        return articulosCarrito;
    }
    
    public void setArticulosCarrito(List<ArticuloCarrito> articulosCarrito) {
        if(articulos != null){
            if (!articulos.isEmpty()) {
                articulos.clear();
            }
        }else{
            articulos = new ArrayList<>();
        }
        
        if(cantidades != null){
            if (!cantidades.isEmpty()) {
                cantidades.clear();
            }
        }else{
            cantidades = new ArrayList<>();
        }

        for (ArticuloCarrito articuloCarrito : articulosCarrito) {
            Articulos articulo = articuloCarrito.getArticulo();
            BigDecimal cantidad = BigDecimal.valueOf(articuloCarrito.getCantidad());

            if (articulo != null && cantidad != null) {
                articulos.add(articulo);
                cantidades.add(cantidad); 
            } 
        }

        // Ensure both lists have the same size after the operation
        if (articulos.size() != cantidades.size()) {
            throw new IllegalStateException("Mismatch between articulos and cantidades sizes.");
        }
    }





}
