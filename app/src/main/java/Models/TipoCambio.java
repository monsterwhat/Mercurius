package Models;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.*;

@Entity
@Data
public class TipoCambio {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private LocalDateTime fecha;

    @Column(nullable = false, precision = 18, scale = 5)
    private BigDecimal valorCompra;

    @Column(nullable = false, precision = 18, scale = 5)
    private BigDecimal valorVenta;

    /** True when set by the user; manual rows freeze the daily BCCR fetch until changed. */
    private Boolean manual;

}