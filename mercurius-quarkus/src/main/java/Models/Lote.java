package Models;

import Models.Articulos.Articulos;
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
@Table(name = "lotes")
public class Lote implements Serializable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "articulo_codigo", nullable = false)
    private Articulos articulo;

    @Column(nullable = false, length = 50)
    private String numeroLote;

    @Column(nullable = false)
    @Temporal(TemporalType.DATE)
    private Date fechaVencimiento;

    @Column(nullable = false, precision = 18, scale = 3)
    private BigDecimal cantidadInicial;

    @Column(nullable = false, precision = 18, scale = 3)
    private BigDecimal cantidadActual;

    @Column(nullable = false)
    @Temporal(TemporalType.TIMESTAMP)
    private Date fechaIngreso;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "usuario_id")
    private Users usuario;

    @Column(length = 100)
    private String notas;

    private Boolean status;
}
