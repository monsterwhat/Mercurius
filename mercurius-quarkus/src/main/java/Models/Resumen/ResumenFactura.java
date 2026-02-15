package Models.Resumen;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlElementWrapper;
import jakarta.xml.bind.annotation.XmlRootElement;
import java.math.BigDecimal;
import java.util.List;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

@XmlAccessorType(XmlAccessType.FIELD)
@XmlRootElement(name = "ResumenFactura")
@Data
@Entity
@Table(name = "resumen_factura")
public class ResumenFactura {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(length = 10)
    private String schemaVersion;

    @XmlElement(name = "CodigoTipoMoneda")
    @Embedded
    private CodigoTipoMoneda codigoMoneda;

    @XmlElement(name = "TotalServGravados")
    @Column(name = "total_servicios_gravados", precision = 18, scale = 5)
    private BigDecimal totalServGravados;

    @XmlElement(name = "TotalServExentos")
    @Column(name = "total_servicios_exentos", precision = 18, scale = 5)
    private BigDecimal totalServExentos;

    @XmlElement(name = "TotalServExonerado")
    @Column(name = "total_servicios_exonerados", precision = 18, scale = 5)
    private BigDecimal totalServExonerado;
    
    @XmlElement(name = "TotalServNoSujeto")
    @Column(name = "total_servicios_no_sujeto", precision = 18, scale = 5)
    private BigDecimal totalServNoSujeto;

    @XmlElement(name = "TotalMercanciasGravadas")
    @Column(name = "total_mercancias_gravadas", precision = 18, scale = 5)
    private BigDecimal totalMercanciasGravadas;

    @XmlElement(name = "TotalMercanciasExentas")
    @Column(name = "total_mercancias_exentas", precision = 18, scale = 5)
    private BigDecimal totalMercanciasExentas;

    @XmlElement(name = "TotalMercExonerada")
    @Column(name = "total_mercancias_exoneradas", precision = 18, scale = 5)
    private BigDecimal totalMercExonerada;
    
    @XmlElement(name = "TotalMercNoSujeta")
    @Column(name = "total_mercancias_no_sujeta", precision = 18, scale = 5)
    private BigDecimal totalMercNoSujeta;

    @XmlElement(name = "TotalGravado")
    @Column(name = "total_gravado", precision = 18, scale = 5)
    private BigDecimal totalGravado;

    @XmlElement(name = "TotalExento")
    @Column(name = "total_exento", precision = 18, scale = 5)
    private BigDecimal totalExento;

    @XmlElement(name = "TotalExonerado")
    @Column(name = "total_exonerado", precision = 18, scale = 5)
    private BigDecimal totalExonerado;
    
    @XmlElement(name = "TotalNoSujeto")
    @Column(name = "total_no_sujeto", precision = 18, scale = 5)
    private BigDecimal totalNoSujeto;

    @XmlElement(name = "TotalVenta")
    @Column(name = "total_venta", precision = 18, scale = 5)
    private BigDecimal totalVenta;

    @XmlElement(name = "TotalDescuentos")
    @Column(name = "total_descuentos", precision = 18, scale = 5)
    private BigDecimal totalDescuentos;

    @XmlElement(name = "TotalVentaNeta")
    @Column(name = "total_venta_neta", precision = 18, scale = 5)
    private BigDecimal totalVentaNeta;
    
    @XmlElementWrapper(name = "TotalDesgloseImpuestos")
    @XmlElement(name = "TotalDesgloseImpuesto")
    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    @OneToMany(mappedBy = "resumenFactura", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    private List<TotalDesgloseImpuesto> totalDesgloseImpuestos;

    @XmlElement(name = "TotalImpuesto")
    @Column(name = "total_impuesto", precision = 18, scale = 5)
    private BigDecimal totalImpuesto;
    
    @XmlElement(name = "TotalImpuestoAsumidoEmisorFabrica")
    @Column(name = "total_impuesto_asumido_emisor_fabrica", precision = 18, scale = 5)
    private BigDecimal totalImpuestoAsumidoEmisorFabrica;

    @XmlElement(name = "TotalIvaDevuelto")
    @Column(name = "total_iva_devuelto", precision = 18, scale = 5)
    private BigDecimal totalIVADevuelto;

    @XmlElement(name = "TotalOtrosCargos")
    @Column(name = "total_otros_cargos", precision = 18, scale = 5)
    private BigDecimal totalOtrosCargos;
    
    @XmlElementWrapper(name = "MediosPago")
    @XmlElement(name = "MedioPago")
    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    @OneToMany(mappedBy="resumenFactura", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    private List<MedioPagoR> mediosPago;

    @XmlElement(name = "TotalComprobante")
    @Column(name = "total_comprobante", precision = 18, scale = 5)
    private BigDecimal totalComprobante;

}
