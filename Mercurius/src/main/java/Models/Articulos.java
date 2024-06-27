package Models;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.util.Date;
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

    @Column(name = "precio_costo_sin_iva")
    private BigDecimal precioCostoSinIVA;

    @Column(name = "precio_costo_con_iva")
    private BigDecimal precioCostoConIVA;

    @Column(name = "porcentaje_utilidad")
    private BigDecimal porcentajeUtilidad;

    @Column(name = "precio_final")
    private BigDecimal precioFinal;
    
    private boolean status;
    
    private boolean processed;
    
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
    
}
