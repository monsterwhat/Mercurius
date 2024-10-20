package Models.Comprobantes.Detalles;

/**
 *
 * @author Al
 */

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
@Entity
@Table(name = "linea_detalle")
public class LineaDetalle {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "numero_linea")
    private Integer numeroLinea;

    @Column(name = "partida_arancelaria", length = 12)
    private String partidaArancelaria;

    @Column(name = "codigoCabys", length = 13)
    private String codigoCabys;

    @OneToMany(mappedBy = "lineaDetalle", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    private List<CodigoComercial> codigosComerciales;

    @Column(name = "cantidad", precision = 16, scale = 3)
    private BigDecimal cantidad;

    @Column(name = "unidad_medida", length = 15)
    private String unidadMedida;

    @Column(name = "unidad_medida_comercial", length = 20)
    private String unidadMedidaComercial;

    @Column(name = "detalle", length = 200)
    private String detalle;
    
    @ManyToOne
    @JoinColumn(name = "detalle_servicio_id")
    private DetalleServicio detalleServicio;

    @Column(name = "precio_unitario", precision = 18, scale = 5)
    private BigDecimal precioUnitario;
    
    //Monto total
    @Column(name = "monto_total", precision = 18, scale = 5)
    private BigDecimal montoTotal;

    @OneToMany(mappedBy = "lineaDetalle", orphanRemoval = true, fetch = FetchType.EAGER)
    private List<Descuento> descuentos;

    @Column(name = "sub_total", precision = 18, scale = 5)
    private BigDecimal subTotal;

    @Column(name = "base_imponible", precision = 18, scale = 5)
    private BigDecimal baseImponible;

    @OneToMany(mappedBy = "lineaDetalle", orphanRemoval = true, fetch = FetchType.EAGER)
    private List<Impuesto> impuestos;
    
    //Monto total de linea
    @Column(name = "monto_total_de_linea", precision = 18, scale = 5)
    private BigDecimal montoTotalLinea;

}

