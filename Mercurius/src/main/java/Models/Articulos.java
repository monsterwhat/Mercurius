package Models;

import jakarta.persistence.*;
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
    private double precioCostoSinIVA;

    @Column(name = "precio_costo_con_iva")
    private double precioCostoConIVA;

    @Column(name = "porcentaje_utilidad")
    private double porcentajeUtilidad;

    @Column(name = "precio_final")
    private double precioFinal;
    
    private boolean status;
    
    private boolean processed;
    
    @ManyToOne
    @JoinColumn(name = "usuario_id")
    private Users usuario; //Referencia a quien creo el Articulo
    
}
