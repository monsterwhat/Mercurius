package Models;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Temporal;
import jakarta.persistence.TemporalType;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Date;
import lombok.Data;

@Entity
@Data
@Table(name = "notacredito")
public class NotaCredito implements Serializable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "comprobante_original_id", nullable = false)
    private ComprobantesEmitidos comprobanteOriginal;

    @Column(nullable = false)
    @Temporal(TemporalType.TIMESTAMP)
    private Date fecha;

    @Column(nullable = false, length = 500)
    private String motivo;

    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal montoTotal;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "cliente_id")
    private Clients cliente;

    @Column(length = 50)
    private String usuario;

    @Column(length = 50)
    private String haciendaClave;

    @Column(length = 20)
    private String haciendaEstado;

    private Boolean status;

    @Temporal(TemporalType.TIMESTAMP)
    private Date fechaAnulacion;
}