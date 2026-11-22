package Services.pronostico;

/**
 * Marca de calidad de un pronostico.
 *
 * <p>Una cifra de demanda sin contexto se lee como una certeza que no tiene.
 * Estas marcas viajan con el pronostico hasta la pagina y el API para que el
 * usuario sepa <em>por que</em> un numero merece mas o menos confianza:</p>
 *
 * <ul>
 *   <li>{@link #INSUFFICIENT_HISTORY}: menos de dos semanas de rejilla o menos
 *       de tres dias con demanda no nula. El modelo puede ejecutarse, pero la
 *       seleccion no tiene muestra para medirse.</li>
 *   <li>{@link #INTERMITTENT_DEMAND}: regimen {@code INTERMITENTE} o
 *       {@code GRUMOSO} del cuadrante ADI / CV^2. El promedio diario es bajo y
 *       los ceros son parte del patron, no errores.</li>
 *   <li>{@link #RECENT_STOCKOUT}: el stock actual es cero. Un cero de venta
 *       durante un quiebre no es necesariamente un cero de demanda.</li>
 *   <li>{@link #DATA_GAP}: hay una racha de mas de cuatro semanas sin ningun
 *       movimiento dentro de la historia observada. Puede ser un cierre, una
 *       importacion incompleta o demanda realmente nula.</li>
 *   <li>{@link #MODEL_UNSTABLE}: el ganador del backtesting empata con el
 *       segundo metodo dentro del margen de inestabilidad. La eleccion es
 *       valida pero no concluyente.</li>
 *   <li>{@link #NO_EVALUATION}: no hay fila de backtesting para este articulo
 *       y horizonte; se uso el candidato de respaldo del regimen.</li>
 *   <li>{@link #LEGACY_NAME_MATCH}: la serie se reconstruyo desde lineas de
 *       factura enlazadas por nombre porque el articulo no tiene movimientos de
 *       inventario. Es el unico caso en que el nombre participa.</li>
 *   <li>{@link #INTERVAL_APPROXIMATE}: el intervalo de prediccion usa la
 *       aproximacion {@code z * sigma * sqrt(h)} en lugar de una distribucion
 *       empirica de errores por horizonte.</li>
 *   <li>{@link #NO_RECENT_PARTICIPATION}: el articulo no vendio nada en la
 *       ventana de participacion de su familia. No se le asigna una cuota
 *       especulativa; su pronostico queda en cero.</li>
 * </ul>
 */
public enum FlagCalidad {

    INSUFFICIENT_HISTORY,
    INTERMITTENT_DEMAND,
    RECENT_STOCKOUT,
    DATA_GAP,
    MODEL_UNSTABLE,
    NO_EVALUATION,
    LEGACY_NAME_MATCH,
    INTERVAL_APPROXIMATE,
    NO_RECENT_PARTICIPATION;

    /** Etiqueta corta y estable para logs y JSON. */
    public String etiqueta() {
        return name();
    }
}
