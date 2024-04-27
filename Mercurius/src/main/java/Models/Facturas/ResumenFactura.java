package Models.Facturas;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import java.math.BigDecimal;
import lombok.Data;

@Entity
@Data
public class ResumenFactura {
    @Id
    private Long id;

    private String codigoTipoMoneda;
    private String codigoMoneda;
    private BigDecimal tipoCambio;
    private BigDecimal totalServGravados;
    private BigDecimal totalServExentos;
    private BigDecimal totalServExonerado;
    private BigDecimal totalMercanciasGravadas;
    private BigDecimal totalMercanciasExentas;
    private BigDecimal totalMercExonerada;
    private BigDecimal totalGravado;
    private BigDecimal totalExento;
    private BigDecimal totalExonerado;
    private BigDecimal totalVenta;
    private BigDecimal totalDescuentos;
    private BigDecimal totalVentaNeta;
    private BigDecimal totalImpuesto;
    private BigDecimal totalComprobante;

}
