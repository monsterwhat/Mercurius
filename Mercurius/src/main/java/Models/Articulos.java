package Models;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.util.Date;
import java.util.List;
import lombok.Data;

@Entity
@Data
public class Articulos {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private int codigo;

    @ManyToOne
    @JoinColumn(name = "codigo_cabys")
    private Cabys codigoCabys;

    private String recomendacionCabys;
    
    private String nombre;
    
    private String detalles;
    
    private String codigoBarra;
    
    private String UnidadMedida;
    
    private String unidadMedidaComercial;

    @ManyToOne
    @JoinColumn(name = "departamento_id")
    private Departamento departamento;

    @ManyToOne
    @JoinColumn(name = "familia_id")
    private Familia familia;
    
    private boolean status;
    
    private boolean processed;
    
    @OneToMany(mappedBy = "articulo", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<ArticuloPrecio> precios; // List of pricing details
    
    @Column(nullable = false)
    @Temporal(TemporalType.TIMESTAMP)
    private Date fecha; //LOGS LOGS LOGS!!
    
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
    
    public BigDecimal getLastPrecioArticulo(){
        ArticuloPrecio lastPrecio = getLastPrecio();
        return lastPrecio.getPrecioCostoConIVA();
    }
    
}
