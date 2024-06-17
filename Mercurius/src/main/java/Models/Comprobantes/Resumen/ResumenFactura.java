
package Models.Comprobantes.Resumen;

import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import lombok.Data;

/**
 *
 * @author Al
 */

@Data
@Entity
@Table(name = "resumen_factura")
public class ResumenFactura {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Embedded
    private CodigoTipoMoneda codigoMoneda;

    @Column(name = "total_servicios_gravados", precision = 18, scale = 5)
    private BigDecimal totalServGravados;

    @Column(name = "total_servicios_exentos", precision = 18, scale = 5)
    private BigDecimal totalServExentos;

    @Column(name = "total_servicios_exonerados", precision = 18, scale = 5)
    private BigDecimal totalServExonerado;

    @Column(name = "total_mercancias_gravadas", precision = 18, scale = 5)
    private BigDecimal totalMercanciasGravadas;

    @Column(name = "total_mercancias_exentas", precision = 18, scale = 5)
    private BigDecimal totalMercanciasExentas;

    @Column(name = "total_mercancias_exoneradas", precision = 18, scale = 5)
    private BigDecimal totalMercExonerada;

    @Column(name = "total_gravado", precision = 18, scale = 5)
    private BigDecimal totalGravado;

    @Column(name = "total_exento", precision = 18, scale = 5)
    private BigDecimal totalExento;

    @Column(name = "total_exonerado", precision = 18, scale = 5)
    private BigDecimal totalExonerado;

    @Column(name = "total_venta", precision = 18, scale = 5)
    private BigDecimal totalVenta;

    @Column(name = "total_descuentos", precision = 18, scale = 5)
    private BigDecimal totalDescuentos;

    @Column(name = "total_venta_neta", precision = 18, scale = 5)
    private BigDecimal totalVentaNeta;

    @Column(name = "total_impuesto", precision = 18, scale = 5)
    private BigDecimal totalImpuesto;

    @Column(name = "total_iva_devuelto", precision = 18, scale = 5)
    private BigDecimal totalIVADevuelto;

    @Column(name = "total_otros_cargos", precision = 18, scale = 5)
    private BigDecimal totalOtrosCargos;

    @Column(name = "total_comprobante", precision = 18, scale = 5)
    private BigDecimal totalComprobante;

}
