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
 * <p><b>Una fila por (articulo, metodo) por corrida de backtesting.</b> No es
 * un histórico incremental: cada evaluación borra las filas previas del
 * artículo ({@code SeleccionMetodoService.evaluarArticulo}) y vuelve a
 * insertar una fila por cada método candidato elegible, de modo que la tabla
 * siempre refleja la última corrida y nunca duplica resultados. El
 * desempate entre métodos con el mismo MASE se resuelve con un orden de
 * simplicidad fijo, por lo que el conjunto de filas de un artículo es
 * determinístico.</p>
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
        @Index(name = "idx_precision_pronostico_articulo", columnList = "articulo_codigo")
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

    /** Momento en que se ejecutó el backtesting que produjo esta fila. */
    @Column(name = "fecha_calculo", nullable = false)
    private LocalDateTime fechaCalculo;
}
