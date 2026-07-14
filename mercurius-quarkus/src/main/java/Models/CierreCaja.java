package Models;

import jakarta.annotation.Nullable;
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
@Table(name = "cierrecaja")
public class CierreCaja implements Serializable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "usuario_id", nullable = false)
    private Users usuario;

    @Column(nullable = false)
    @Temporal(TemporalType.TIMESTAMP)
    private Date fechaApertura;

    @Nullable
    @Temporal(TemporalType.TIMESTAMP)
    private Date fechaCierre;

    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal montoInicial;

    @Nullable
    @Column(precision = 18, scale = 2)
    private BigDecimal montoEsperadoEfectivo;

    @Nullable
    @Column(precision = 18, scale = 2)
    private BigDecimal montoEsperadoSinpe;

    @Nullable
    @Column(precision = 18, scale = 2)
    private BigDecimal montoEsperadoTarjeta;

    @Nullable
    @Column(precision = 18, scale = 2)
    private BigDecimal montoContadoEfectivo;

    @Nullable
    @Column(precision = 18, scale = 2)
    private BigDecimal montoContadoSinpe;

    @Nullable
    @Column(precision = 18, scale = 2)
    private BigDecimal montoContadoTarjeta;

    @Nullable
    @Column(precision = 18, scale = 2)
    private BigDecimal diferencia;

    @Column(nullable = false, length = 20)
    private String estado; // "abierto" or "cerrado"

    private String notas;
}