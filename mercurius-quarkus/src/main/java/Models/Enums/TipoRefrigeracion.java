package Models.Enums;

/**
 * Tipo de refrigeración del producto. Determina el ajuste de margen
 * de utilidad que se aplica sobre el margen base global.
 *
 *   NINGUNA      - Sin refrigeración (aplica solo margen base)
 *   REFRIGERADO  - Refrigerado 2-8°C (aplica margen base + ajusteRefrigerado)
 *   CONGELADO    - Congelado -18°C (aplica margen base + ajusteCongelado)
 */
public enum TipoRefrigeracion {
    NINGUNA,
    REFRIGERADO,
    CONGELADO
}
