package Models;

import java.math.BigDecimal;
import jakarta.annotation.Nonnull;
import lombok.Data;

/** A single payment method entry (code + amount) for split payment support */
@Data
public class PagoEntry {
    @Nonnull
    private String metodoPago = "01";
    @Nonnull
    private BigDecimal monto = BigDecimal.ZERO;

    public static String metodoPagoLabel(@Nonnull String code) {
        return switch (code) {
            case "01" -> "Efectivo";
            case "02" -> "Tarjeta";
            case "03" -> "Cheque";
            case "04" -> "Transferencia/Depósito";
            case "05" -> "Recaudado por Terceros";
            case "06" -> "SINPE Móvil";
            case "07" -> "Plataforma Digital";
            case "08" -> "Billetera Electrónica";
            case "10" -> "Crédito";
            case "99" -> "Otros";
            default -> code;
        };
    }
}
