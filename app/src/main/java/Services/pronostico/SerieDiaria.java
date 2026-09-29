package Services.pronostico;

import java.time.LocalDate;

/**
 * Observacion diaria de demanda de un articulo.
 *
 * <p>Origen: movimientos de {@code Inventario} con {@code tipoMovimiento = 'Venta'}, agregados por
 * articulo y por dia. Se admite que existan dias ausentes en la lista (se completan con 0 al
 * construir la rejilla diaria) y fechas repetidas (se suman).
 *
 * @param fecha    dia natural de la observacion
 * @param cantidad unidades vendidas; valores 0 se consideran "sin demanda" a efectos de ADI/CV^2
 */
public record SerieDiaria(LocalDate fecha, long cantidad) {
}
