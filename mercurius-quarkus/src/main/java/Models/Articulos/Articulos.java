package Models.Articulos;

import Models.Cabys;
import Models.Departamento;
import Models.Familia;
import Models.Users;
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
    @ManyToOne
    @JoinColumn(name = "cabys_id")
    private Cabys codigoCabys;
    
    @Column
    private String nombre;

    @Column
    private String codigoBarra;

    @Column(length = 500)
    private String descripcion;

    @Column
    private String UnidadMedida;

    @Column
    private String unidadMedidaComercial;

    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    @ManyToOne
    @JoinColumn(name = "departamento_id")
    private Departamento departamento;

    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    @ManyToOne
    @JoinColumn(name = "familia_id")
    private Familia familia;

    @Column
    private boolean status;

    @Column
    private boolean processed;

    // Stock management fields - automatically calculated by system
    @Column(name = "stockOptimo")
    private Integer stockOptimo; // Calculated optimal stock level
    
    @Column(name = "diasStockSeguridad")
    private Integer diasStockSeguridad; // Safety stock days (default: 7)
    
    @Column(name = "estadoAlertas")
    private Boolean estadoAlertas = true; // Enable stock alerts

    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    @OneToMany(mappedBy = "articulo", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    private List<ArticuloPrecio> precios; // List of pricing details
  
    @Column(nullable = false)
    @Temporal(TemporalType.TIMESTAMP)
    private Date fecha; //LOGS LOGS LOGS!!

    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    @ManyToOne
    @JoinColumn(name = "usuario_id")
    private Users usuario; //Referencia a quien creo el Articulo

    @PrePersist
    protected void onCreate() {
        fecha = new Date(); // Sets the current timestamp when creating the entity
    }

    public ArticuloPrecio getLastPrecio() {
        if (precios != null && !precios.isEmpty()) {
            return precios.get(precios.size() - 1);
        }
        return null;
    }

    public BigDecimal getLastPrecioArticulo() {
        ArticuloPrecio lastPrecio = getLastPrecio();
        return lastPrecio.getPrecioFinal();
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
