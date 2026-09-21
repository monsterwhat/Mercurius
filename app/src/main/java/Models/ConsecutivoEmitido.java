package Models;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Data;

/**
 * Per-type counter for emitted document consecutive numbering (FE, TE, NC, ND, FEE, FEC, REP).
 *
 * One row per (sucursal, terminal, tipo) combination.
 * Each document type at each point of sale gets its own independent sequence starting from 1,
 * as required by Hacienda CR: "valor consecutivo que se debe de generar por tipo de documento...
 * Siempre comienza desde 1 por cada punto de venta y por cada tipo de documento."
 *
 * Uses PESSIMISTIC_WRITE locking in ConsecutivoEmitidoService to guarantee
 * unique sequential numbers under concurrent requests.
 */
@Data
@Entity
@Table(name = "consecutivo_emitido", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"sucursal", "terminal", "tipo"})
})
public class ConsecutivoEmitido {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sucursal", length = 3, nullable = false)
    private String sucursal;

    @Column(name = "terminal", length = 5, nullable = false)
    private String terminal;

    @Column(name = "tipo", length = 2, nullable = false)
    private String tipo;

    @Column(name = "ultimo_secuencial", nullable = false)
    private Long ultimoSecuencial = 0L;
}
