package Models.ComprobantesV44.Detalles;

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
import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlElementWrapper;
import jakarta.xml.bind.annotation.XmlRootElement;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;
import lombok.EqualsAndHashCode;
import lombok.ToString;

@XmlAccessorType(XmlAccessType.FIELD)
@XmlRootElement(name = "LineaDetalle")
@Data
@Entity
@Table(name = "linea_detalle")
public class LineaDetalle {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    @ManyToOne
    @JoinColumn(name = "detalle_servicio_id")
    private DetalleServicio detalleServicio;
    
    @XmlElement(name = "NumeroLinea")
    @Column(name = "numero_linea")
    private Integer numeroLinea;

    @XmlElement(name = "PartidaArancelaria")
    @Column(name = "partida_arancelaria", length = 12)
    private String partidaArancelaria;

    @XmlElement(name = "CodigoCabys")
    @Column(name = "codigoCabys", length = 13)
    private String codigoCabys;

    @XmlElementWrapper(name = "CodigosComerciales")
    @XmlElement(name = "CodigoComercial")
    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    @OneToMany(mappedBy = "lineaDetalle", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    private List<CodigoComercial> codigosComerciales;

    @XmlElement(name = "Cantidad")
    @Column(name = "cantidad", precision = 16, scale = 3)
    private BigDecimal cantidad;

    @XmlElement(name = "UnidadMedida")
    @Column(name = "unidad_medida", length = 15)
    private String unidadMedida;
    
    @XmlElement(name = "TipoTransaccion")
    @Column(name = "tipo_transaccion", length = 2)
    private String tipoTransaccion;

    @XmlElement(name = "UnidadMedidaComercial")
    @Column(name = "unidad_medida_comercial", length = 20)
    private String unidadMedidaComercial;

    @XmlElement(name = "Detalle")
    @Column(name = "detalle", length = 200)
    private String detalle;
    
    //TODO from 1 to 1000
    @XmlElement(name = "NumerosVINoSerie")
    @Column(name = "numeros_vi_no_serie", length = 17)
    private List<String> numerosVINoSerie;
    
    @XmlElement(name = "RegistroMedicamento")
    @Column(name = "registro_medicamento", length = 100)
    private String registroMedicamento;
    
    @XmlElement(name = "FormaFarmaceutica")
    @Column(name = "forma_farmaceutica", length = 3)
    private String formaFarmaceutica;
    
    //TODO from 1 to 20
    @XmlElementWrapper(name = "DetallesSurtidos")
    @XmlElement(name = "DetalleSurtido")
    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    @ManyToOne
    @JoinColumn(name = "detalle_surtido_id")
    private DetalleSurtido detallesSurtidos;
 
    @XmlElement(name = "PrecioUnitario")
    @Column(name = "precio_unitario", precision = 18, scale = 5)
    private BigDecimal precioUnitario;
   
    @XmlElement(name = "MontoTotal")
    @Column(name = "monto_total", precision = 18, scale = 5)
    private BigDecimal montoTotal;

    //0-5
    @XmlElementWrapper(name = "Descuentos")
    @XmlElement(name = "Descuento")
    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    @OneToMany(mappedBy = "lineaDetalle", orphanRemoval = true, fetch = FetchType.EAGER)
    private List<Descuento> descuentos;

    @XmlElement(name = "SubTotal")
    @Column(name = "sub_total", precision = 18, scale = 5)
    private BigDecimal subTotal;
    
    @XmlElement(name = "IVACobradoFabrica")
    @Column(name = "iva_cobrado_fabrica", length = 2)
    private String ivaCobradoFabrica;

    @XmlElement(name = "BaseImponible")
    @Column(name = "base_imponible", precision = 18, scale = 5)
    private BigDecimal baseImponible;

    // 1-1000
    @XmlElementWrapper(name = "Impuestos")
    @XmlElement(name = "Impuesto")
    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    @OneToMany(mappedBy = "lineaDetalle", orphanRemoval = true, fetch = FetchType.EAGER)
    private List<Impuesto> impuestos;
    
    @XmlElement(name = "ImpuestoAsumidoEmisorFabrica")
    @Column(name = "impuesto_asumido_emisor_fabrica", precision = 18, scale = 5)
    private BigDecimal impuestoAsumidoEmisorFabrica;
    
    @XmlElement(name = "ImpuestoNeto")
    @Column(name = "impuesto_neto", precision = 18, scale = 5)
    private BigDecimal impuestoNeto;
     
    @XmlElement(name = "MontoTotalLinea")
    @Column(name = "monto_total_de_linea", precision = 18, scale = 5)
    private BigDecimal montoTotalLinea;
        
}

