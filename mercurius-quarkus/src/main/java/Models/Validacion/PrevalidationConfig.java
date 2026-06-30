package Models.Validacion;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import lombok.Data;

/**
 * DB-based configuration for pre-validation and auto-correction.
 * Replaces hardcoded values in ComprobantesRecibidosPrevalidationService.
 *
 * Only one config should be active at a time (isActive = true).
 */
@Data
@Entity
@Table(name = "prevalidation_config")
public class PrevalidationConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** STRICT = missing CAByS codes are errors (reject invoice);
     *  LENIENT = missing CAByS codes are warnings (allow acceptance). */
    @Column(name = "cabys_strict_mode", nullable = false)
    private boolean cabysStrictMode = true;

    /** Tolerance for tax calculation comparisons (e.g. 0.01 = ±1 colón). */
    @Column(name = "tax_tolerance", precision = 10, scale = 4, nullable = false)
    private BigDecimal taxTolerance = new BigDecimal("0.01");

    /** Whether to warn on minor rounding differences within tolerance. */
    @Column(name = "warn_on_rounding")
    private boolean warnOnRounding = false;

    /** Max auto-correction attempts for emitted invoices rejected by Hacienda. */
    @Column(name = "max_correction_attempts", nullable = false)
    private int maxCorrectionAttempts = 3;

    /** Whether this config is the active one (only one should be active at a time). */
    @Column(name = "is_active", nullable = false)
    private boolean isActive = true;

    /** Human-readable label for this config profile. */
    @Column(name = "profile_name", length = 100)
    private String profileName = "default";
}
