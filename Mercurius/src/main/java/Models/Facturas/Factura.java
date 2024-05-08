package Models.Facturas;

import Models.Users;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToOne;
import lombok.Data;

@Entity
@Data
public class Factura {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY) // Use auto-increment strategy
    private Long id;

    private String codigoActividad;
    private String numeroConsecutivo;
    private String fechaEmision;

    @OneToOne
    private Emisor emisor;

    @OneToOne
    private Receptor receptor;

    private String condicionVenta;
    private String plazoCredito;
    private String medioPago;

    @OneToOne
    private DetalleServicio detalleServicio;

    @OneToOne
    private ResumenFactura resumenFactura;
    
    private Boolean status;
    
    private Users user;

}
