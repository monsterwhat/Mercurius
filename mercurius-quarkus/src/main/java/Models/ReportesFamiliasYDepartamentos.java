package Models;

import java.math.BigDecimal;
import java.util.List;
import lombok.Data;

/**
 *
 * @author Al
 */

@Data
public class ReportesFamiliasYDepartamentos {
    
    String nombre;
    BigDecimal cantidad;
    BigDecimal porcentaje;

    public ReportesFamiliasYDepartamentos(String nombre, BigDecimal cantidad, BigDecimal porcentaje) {
        this.nombre = nombre;
        this.cantidad = cantidad;
        this.porcentaje = porcentaje;
    }
    
    public static BigDecimal totalReportes(List<ReportesFamiliasYDepartamentos> reportes) {
        BigDecimal total = BigDecimal.ZERO;

        for (ReportesFamiliasYDepartamentos report : reportes) {
            total = total.add(report.getCantidad());
        }

        return total;
    }
    
}
