package Models;

import Models.Articulos.Articulos;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

/**
 * Precisión de un método de pronóstico sobre un artículo, medida por
 * backtesting con orígenes móviles (<em>rolling origins</em>).
 *
 * <p><b>Una fila por (articulo, horizonte, metodo) por corrida de
 * backtesting.</b> No es un historico incremental: cada evaluacion borra las
 * filas previas del mismo (articulo, horizonte)
 * ({@code SeleccionMetodoService.evaluarArticulo}) y vuelve a insertar una
 * fila por cada metodo candidato elegible, de modo que la tabla siempre
 * refleja la ultima corrida y nunca duplica resultados. La fila ganadora lleva
 * {@code seleccionado = true}; las demas se conservan para auditoria y
 * desempate.</p>
 *
 * <p>Columnas de interés:</p>
 * <ul>
 *   <li>{@code metodo}: nombre del enumerado
 *       {@code Services.pronostico.MotorPronostico.MetodoPronostico}. Se
 *       guarda como texto (no como {@code @Enumerated}) para que la tabla sea
 *       legible en SQL y tolere la incorporación de métodos nuevos sin
 *       migración.</li>
 *   <li>{@code mase}: error absoluto medio escalado. Es relativo —1.0 significa
 *       "igual que el ingenuo"—, por lo que un valor menor que 1 indica que el
 *       método supera la referencia ingenua. Se almacena {@code null}
 *       (nunca {@code NaN}, que PostgreSQL representa como {@code 'NaN'})
 *       cuando el MASE no está definido, típicamente porque la escala
 *       in-sample ingenuo es cero (serie plana).</li>
 *   <li>{@code sesgo}: error medio escalado por la misma escala, con la
 *       convención de signo de
 *       {@code Services.pronostico.MotorPronostico.sesgoEscalado}:
 *       {@code real - pronostico}. Un valor positivo significa subpronóstico (se
 *       repone de más por falta de reposición) y uno negativo sobrepronóstico
 *       (exceso de stock inmovilizado); el intervalo (-1, 1) se considera
 *       calibrado. {@code null} cuando la escala no está definida.</li>
 *   <li>{@code horizonteDias}: días hacia adelante evaluados en cada origen.
 *       Se conserva por fila para que corridas con distinto horizonte puedan
 *       compararse aunque se pisen entre sí.</li>
 * </ul>
 *
 * <p>La selección del método ganador ({@code metodoElegido}) lee siempre el
 * MASE más bajo no nulo, con desempate por simplicidad.</p>
 *
 * @author Mercurius
 */
@Entity
@Data
@Table(name = "precision_pronostico", indexes = {
        @Index(name = "idx_precision_pronostico_articulo", columnList = "articulo_codigo"),
        @Index(name = "idx_precision_pronostico_articulo_horizonte",
                columnList = "articulo_codigo, horizonte_dias")
})
public class PrecisionPronostico implements Serializable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private long id;

    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "articulo_codigo", nullable = false)
    private Articulos articulo;

    /** Nombre del constante de {@code MetodoPronostico} evaluada. */
    @Column(name = "metodo", nullable = false, length = 40)
    private String metodo;

    /**
     * Error absoluto medio escalado contra la referencia ingenua in-sample.
     * {@code null} cuando la escala es cero o el horizonte no pudo evaluarse.
     */
    @Column(name = "mase")
    private Double mase;

    /**
     * Error medio escalado con la convención {@code real - pronostico} de
     * {@code MotorPronostico.sesgoEscalado}. Positivo = subpronóstico.
     * {@code null} cuando la escala in-sample no está definida.
     */
    @Column(name = "sesgo")
    private Double sesgo;

    /** Días hacia adelante evaluados en cada origen de la corrida. */
    @Column(name = "horizonte_dias", nullable = false)
    private int horizonteDias;

    /**
     * Error absoluto medio en unidades por dia. Se guarda junto al MASE para
     * que la pagina pueda mostrar una cifra en la unidad del negocio sin
     * tener que recuperar la escala ingenua.
     */
    @Column(name = "mae")
    private Double mae;

    /** Numero efectivo de origenes moviles usados en la corrida. */
    @Column(name = "origenes")
    private Integer origenes;

    /** Numero de observaciones reales comparadas (origenes x horizonte). */
    @Column(name = "observaciones")
    private Integer observaciones;

    /** Ultimo dia con historia utilizada en la evaluacion. */
    @Column(name = "fecha_fin_historia")
    private java.time.LocalDate fechaFinHistoria;

    /** Version del contrato/pipeline que produjo la fila. */
    @Column(name = "modelo_version", length = 40)
    private String modeloVersion;

    /** Banderas de calidad separadas por coma (ver {@code FlagCalidad}). */
    @Column(name = "calidad_flags", length = 255)
    private String calidadFlags;

    /**
     * Mejora relativa del ganador sobre la mejor linea base
     * ({@code (maseBase - maseGanador) / maseBase}). Negativa significa que no
     * supero la base y se eligio la base por politica.
     */
    @Column(name = "mejora_baseline")
    private Double mejoraBaseline;

    /** Metodo de linea base contra el que se comparo el ganador. */
    @Column(name = "metodo_baseline", length = 40)
    private String metodoBaseline;

    /**
     * Marca el ganador de la corrida para este (articulo, horizonte). Las filas
     * restantes se conservan para auditoria y desempate. {@code null} en filas
     * antiguas o sembradas a mano: el lector aplica la politica de seleccion.
     */
    @Column(name = "seleccionado")
    private Boolean seleccionado;

    /** Momento en que se ejecutó el backtesting que produjo esta fila. */
    @Column(name = "fecha_calculo", nullable = false)
    private LocalDateTime fechaCalculo;
}
