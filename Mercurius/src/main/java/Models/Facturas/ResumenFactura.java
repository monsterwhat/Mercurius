package Models.Facturas;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import lombok.Data;

@Entity
@Data
public class ResumenFactura {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY) // Use auto-increment strategy
    private Long id;

    private String codigoMoneda;
    private String tipoCambio;
    private String totalServGravados;
    private String totalServExentos;
    private String totalServExonerado;
    private String totalMercanciasGravadas;
    private String totalMercanciasExentas;
    private String totalMercExonerada;
    private String totalGravado;
    private String totalExento;
    private String totalExonerado;
    private String totalVenta;
    private String totalDescuentos;
    private String totalVentaNeta;
    private String totalImpuesto;
    private String totalIVADevuelto;
    private String totalOtrosCargos;
    private String totalComprobante;

}
