package Models.Facturas;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import java.util.Date;
import java.util.List;
import lombok.Data;

@Entity
@Data
public class Factura {
    @Id
    private Long id;

    private String codigoActividad;
    private String numeroConsecutivo;
    private Date fechaEmision;

    @OneToOne(cascade = CascadeType.ALL)
    private Emisor emisor;

    @OneToOne(cascade = CascadeType.ALL)
    private Receptor receptor;

    private String condicionVenta;
    private String plazoCredito;
    private String medioPago;

    @OneToMany(mappedBy = "factura", cascade = CascadeType.ALL)
    private List<DetalleServicio> detalleServicios;

    @OneToOne(cascade = CascadeType.ALL)
    private ResumenFactura resumenFactura;

}