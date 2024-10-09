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
    
    private BigDecimal descuento; // Porcentaje o monto de descuento, puede ser nulo si es combo
    
    private BigDecimal cantidad; // En caso de que sea hasta agotar existencias(Limitada)
    
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
    
    public BigDecimal getCantidad(Articulos articulo) {
        int index = articulos.indexOf(articulo);
        if (index != -1 && index < cantidades.size()) {
            return cantidades.get(index);
        }
        return BigDecimal.ZERO; // Or handle this case as needed
    }
    
    public List<Date> getFechas(){
        List<Date> fechas = new ArrayList<>();
            if(fechaInicio != null){
                fechas.add(fechaInicio);
            }
            if(fechaFin != null){
                fechas.add(fechaFin);
            }
        return fechas;
    }

    public List<ArticuloCarrito> getArticulosCarrito() {
    List<ArticuloCarrito> articulosCarrito = new ArrayList<>();

        // Check if articulos and cantidades are not null before proceeding
        if (this.articulos != null && this.cantidades != null) {
            for (int i = 0; i < this.articulos.size(); i++) {
                Articulos articulo = this.articulos.get(i);
                Double cantidad = this.cantidades.get(i).doubleValue();

                // Create a new ArticuloCarrito for each iteration
                ArticuloCarrito articuloCarrito = new ArticuloCarrito();
                articuloCarrito.setArticulo(articulo);
                articuloCarrito.setCantidad(cantidad);

                // Add the new ArticuloCarrito to the list
                articulosCarrito.add(articuloCarrito);
            }
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
    
    public BigDecimal getTotalPromo(List<ArticuloCarrito> lista, BigDecimal descuento) {
        try {
            
            BigDecimal totalPromo = BigDecimal.ZERO;

            if(lista != null){
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
                    totalPromo = totalPromo.add(precioConUtilidadEIVA.multiply(BigDecimal.valueOf(articulo.getCantidad())));
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

    // In Promocion class
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("Promocion{")
          .append("id=").append(id)
          .append(", nombre='").append(nombre).append('\'')
          .append(", descuento=").append(descuento)
          .append(", activa=").append(activa)
          .append(", articulosCount=").append(articulos != null ? articulos.size() : 0)
          .append('}');

        return sb.toString();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        Promocion promocion = (Promocion) o;

        // Use the primary key 'id' for equality check
        return id == promocion.id;
    }

    @Override
    public int hashCode() {
        return Integer.hashCode(id); // Generate hash based on 'id'
    }

    


}
