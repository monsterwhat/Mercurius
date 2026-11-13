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

    /**
     * Moneda de origen de la tasa: USD (dólar) o EUR (euro). El valor se
     * interpreta como colones por 1 unidad de esta moneda. Las filas históricas
     * sin columna se backfillean a USD.
     */
    @Column(length = 3, columnDefinition = "varchar(3) default 'USD'")
    private String moneda = "USD";

    /** True when set by the user; manual rows freeze the daily BCCR fetch until changed. */
    private Boolean manual;

}