package Services;

import Models.Articulos.Articulos;
import Models.Inventario;
import Models.PrecisionPronostico;
import Services.pronostico.MetodoPronostico;
import Services.pronostico.MotorPronostico;
import Services.pronostico.Regimen;
import Services.pronostico.SerieDiaria;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import jakarta.transaction.UserTransaction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cobertura de {@link SeleccionMetodoService}: la eleccion por backtesting con origenes moviles
 * y su persistencia en {@link PrecisionPronostico}.
 *
 * <p><b>Por que NO {@code @TestTransaction}.</b> El servicio abre su propia transaccion (el
 * interceptor de {@code @Transactional} corre en otro hilo logico que el queerial el test), de
 * modo que unos fixtures sin commit serian invisibles para el. Por eso —igual que en
 * {@code StockAlertConfigResourceTest}— los fixtures se COMITEAN por codigo y se borran en un
 * finally con EntityManager+UserTransaction.</p>
 *
 * <p><b>Aislamiento.</b> Las pruebas de @QuarkusTest comparten la base de datos mercurius_test, asi
 * que cada articulo lleva un sufijo unico (AtomicInteger + nombre del metodo) en nombre y codigo
 * de barra, y sus filas se eliminan siempre en el finally. El nombre del articulo importa de verdad:
 * {@code Inventario.articulo} apunta por id, pero cualquier otro test que filtre por nombre
 * veria el patron semanal sembrado aqui.</p>
 *
 * <p><b>Serie sembrada.</b> 60 dias de ventas con patron semanal explicito —2 unidades de lunes a
 * viernes, 8 el sabado y el domingo— sin dias en cero (una sola fila por dia). Es la serie que
 * hace util la seleccion: tiene ciclo semanal real, asi que un metodo ingenuo se queda corto y
 * los candidatos del regimen compiten de verdad. El patron se construye con el MISMO helper que
 * siembra los movimientos, de modo que la serie que el servicio reconstruye y la que el test
 * usa para calcular los candidatos esperados no pueden divergir.</p>
 */
@QuarkusTest
@Tag("integration-services")
class SeleccionMetodoTest {

    /** Dias de historia sembrados. Suficiente para dos ciclos semanales largos. */
    private static final int DIAS_SEMBRADOS = 60;

    /** Unidades vendidas de dia habil. */
    private static final double VENTA_DIA_HABIL = 2.0;

    /** Unidades vendidas de sabado y domingo. */
    private static final double VENTA_FIN_DE_SEMANA = 8.0;

    private static final AtomicInteger SUFIJOS = new AtomicInteger();

    @Inject
    SeleccionMetodoService seleccionMetodoService;

    @Inject
    ArticulosService articulosService;

    @Inject
    InventarioService inventarioService;

    @Inject
    EntityManager em;

    @Inject
    UserTransaction utx;

    // ── escenarios ──────────────────────────────────────────────────────

    /**
     * Una fila por candidato elegible, todas las metricas utilizables y un metodo ganador.
     *
     * <p>El numero esperado de filas no se fija a mano: se recalcula con
     * {@code candidatos(clasificar(serie))} sobre la misma serie sembrada. Los tres metodos del
     * regimen (cualquiera que sea) son elegibles con 45+ observaciones en cada origen, asi que
     * deben aparecer todos.</p>
     */
    @Test
    void evaluaYGuardaUnaFilaPorCandidatoElegible() {
        Long codigo = null;
        try {
            codigo = sembrarArticuloConVentas("evalua");
            List<SerieDiaria> serie = serieEsperada();

            Regimen regimen = MotorPronostico.clasificar(serie);
            List<MetodoPronostico> esperados = MotorPronostico.candidatos(regimen);
            assertFalse(esperados.isEmpty(), "el regimen " + regimen + " debe proponer candidatos");
            for (MetodoPronostico metodo : esperados) {
                assertTrue(MotorPronostico.elegible(metodo, serie),
                        "el candidato " + metodo + " debe ser elegible sobre " + serie.size() + " observaciones");
            }

            int guardadas = seleccionMetodoService.evaluarArticulo(codigo, 14, 3);
            List<PrecisionPronostico> filas = leerPrecision(codigo);

            assertEquals(esperados.size(), guardadas,
                    "debe guardarse exactamente una fila por candidato elegible del regimen " + regimen);
            assertEquals(esperados.size(), filas.size(),
                    "la tabla debe contener una fila por candidato, sin duplicados");

            Set<String> nombres = new HashSet<>();
            for (PrecisionPronostico fila : filas) {
                nombres.add(fila.getMetodo());
                assertEquals(14, fila.getHorizonteDias(), "la fila debe conservar el horizonte evaluado");
                assertNotNull(fila.getFechaCalculo(), "fechaCalculo es NOT NULL");
                assertNotNull(fila.getArticulo(), "la fila debe apuntar a su articulo");
                // NaN se persiste en PostgreSQL como el texto 'NaN': lo indefinido va como null.
                assertTrue(fila.getMase() == null || Double.isFinite(fila.getMase()),
                        "MASE debe ser finito o null, fue " + fila.getMase() + " en " + fila.getMetodo());
                assertTrue(fila.getSesgo() == null || Double.isFinite(fila.getSesgo()),
                        "el sesgo debe ser finito o null, fue " + fila.getSesgo() + " en " + fila.getMetodo());
            }
            assertEquals(esperados.size(), nombres.size(), "no puede haber dos filas del mismo metodo");
            for (MetodoPronostico metodo : esperados) {
                assertTrue(nombres.contains(metodo.name()), "falta la fila del candidato " + metodo);
            }

            // Con una serie de patron semanal la escala ingenua no es cero, asi que el MASE
            // tiene que estar definido para al menos un metodo y la eleccion no puede ir vacia.
            assertTrue(filas.stream().anyMatch(f -> f.getMase() != null),
                    "con historia no plana debe haber al menos un MASE definido");
            Optional<String> elegido = seleccionMetodoService.metodoElegido(codigo);
            assertTrue(elegido.isPresent(), "debe haber un metodo elegido");
            assertTrue(nombres.contains(elegido.get()),
                    "el metodo elegido (" + elegido.get() + ") debe ser uno de los evaluados");
        } finally {
            limpiar(codigo);
        }
    }

    /**
     * Repetir la evaluacion no duplica filas: la corrida reemplaza por completo las anteriores
     * (borra y reinserta), de modo que la tabla sigue teniendo una fila por candidato.
     */
    @Test
    void reevaluarNoDuplicaFilas() {
        Long codigo = null;
        try {
            codigo = sembrarArticuloConVentas("idempotente");

            int primera = seleccionMetodoService.evaluarArticulo(codigo, 14, 3);
            List<PrecisionPronostico> filasPrimera = leerPrecision(codigo);
            assertTrue(primera > 0, "la primera evaluacion debe guardar filas");
            String elegidoPrimera = seleccionMetodoService.metodoElegido(codigo).orElse(null);
            Set<Long> idsPrimera = new HashSet<>();
            for (PrecisionPronostico fila : filasPrimera) {
                idsPrimera.add(fila.getId());
            }

            int segunda = seleccionMetodoService.evaluarArticulo(codigo, 14, 3);
            List<PrecisionPronostico> filasSegunda = leerPrecision(codigo);

            assertEquals(primera, segunda, "la segunda corrida debe guardar las mismas filas");
            assertEquals(primera, filasSegunda.size(),
                    "tras la segunda corrida debe haber exactamente " + primera + " fila(s), no "
                            + filasSegunda.size() + " (el borrado previo no se esta aplicando)");
            assertEquals(primera, idsPrimera.size(), "los ids de una misma corrida deben ser distintos entre si");

            Set<Long> idsSegunda = new HashSet<>();
            for (PrecisionPronostico fila : filasSegunda) {
                idsSegunda.add(fila.getId());
            }
            assertTrue(idsPrimera.stream().noneMatch(idsSegunda::contains),
                    "el borrado-y-reinserzo debe emitir filas nuevas, no conservar las anteriores");

            assertEquals(elegidoPrimera, seleccionMetodoService.metodoElegido(codigo).orElse(null),
                    "sobre la misma historia el metodo elegido debe ser estable");
        } finally {
            limpiar(codigo);
        }
    }

    /**
     * El criterio de eleccion es determinista y respeta el desempate por simplicidad: con filas de
     * MASE identico gana el mas simple del orden
     * INGENUO &lt; ESTACIONAL_INGENUO &lt; MEDIA_MOVIL &lt; SES &lt; SBA &lt; HOLT_AMORTIGUADO
     * &lt; HOLT_WINTERS. Se comprueba sembrando un empate artificial en lugar de confiar en que la
     * serie produzca un empate natural, y de paso que un MASE {@code null} queda fuera de la
     * eleccion.
     */
    @Test
    void desempataPorSimplicidadConMaseIgual() {
        Long codigo = null;
        try {
            codigo = sembrarArticuloSinVentas("desempate");
            guardar(
                    fila(codigo, "HOLT_WINTERS", 0.75, 0.1),
                    fila(codigo, "ESTACIONAL_INGENUO", 0.75, 0.0),
                    fila(codigo, "INGENUO", 0.75, -0.2),
                    fila(codigo, "SBA", null, null));

            assertEquals("INGENUO", seleccionMetodoService.metodoElegido(codigo).orElse(null),
                    "a igualdad de MASE debe ganar el metodo mas simple");
        } finally {
            limpiar(codigo);
        }
    }

    /**
     * Sin filas de precision la eleccion es vacia, y con MASE definido se elige el mas bajo.
     * Cierra el contrato de {@code metodoElegido} sobre la consulta y su filtro de nulos.
     */
    @Test
    void metodoElegidoVacioSinPrecisionYTomaElMaseMasBajo() {
        Long codigo = null;
        try {
            codigo = sembrarArticuloSinVentas("vacio");
            assertTrue(seleccionMetodoService.metodoElegido(codigo).isEmpty(),
                    "un articulo sin evaluar no debe devolver metodo");

            guardar(
                    fila(codigo, "SES", 1.9, 0.4),
                    fila(codigo, "MEDIA_MOVIL", 0.6, 0.0),
                    fila(codigo, "HOLT_AMORTIGUADO", 1.2, -0.3));

            assertEquals("MEDIA_MOVIL", seleccionMetodoService.metodoElegido(codigo).orElse(null),
                    "debe ganar el MASE mas bajo, no el primero insertado");
        } finally {
            limpiar(codigo);
        }
    }

    /**
     * Un articulo sin historia vendible no deja filas de precision, y la reevaluacion semanal
     * completa no lo cuenta como reevaluado. Cubre el salto barato por historia vacia de
     * {@code reelegirTodo()}, que borra lo que hubiera quedado de corridas previas.
     */
    @Test
    void articuloSinVentasNoGeneraPrecision() {
        Long codigo = null;
        try {
            codigo = sembrarArticuloSinVentas("sinVentas");

            assertEquals(0, seleccionMetodoService.evaluarArticulo(codigo, 14, 3),
                    "sin ventas no hay nada que evaluar");
            assertTrue(leerPrecision(codigo).isEmpty(), "no debe quedar ninguna fila de precision");
            assertTrue(seleccionMetodoService.metodoElegido(codigo).isEmpty(),
                    "sin filas no hay metodo elegido");

            assertEquals(0, seleccionMetodoService.reelegirTodo(),
                    "reelegirTodo() no debe contar un articulo sin historia");
        } finally {
            limpiar(codigo);
        }
    }

    // ── fixtures ────────────────────────────────────────────────────────

    /**
     * Articulo + 60 dias de movimientos 'Venta' con patron semanal explicito.
     *
     * <p>Las ventas se guardan con {@code cantidad} negativa, que es la convencion de
     * {@code CarritoService} al descontar stock; el servicio toma el valor absoluto, asi que
     * sembrar el signo equivocado daria una serie de demanda negativa y flotante.</p>
     *
     * @return codigo del articulo sembrado
     */
    private Long sembrarArticuloConVentas(String etiqueta) {
        Long codigo = sembrarArticulo(etiqueta);
        for (LocalDate dia : diasSembrados()) {
            double unidades = unidadesDelDia(dia);
            Inventario movimiento = new Inventario();
            movimiento.setArticulo(buscarArticulo(codigo));
            movimiento.setCantidad(BigDecimal.valueOf(-unidades));
            movimiento.setTipoMovimiento("Venta");
            // Mediodia: a caballo de cualquier cambio de horario de verano, para que la
            // conversion a LocalDate no pueda correrse un dia.
            movimiento.setFechaMovimiento(Date.from(dia.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant()));
            movimiento.setNotas("IT SeleccionMetodo " + etiqueta);
            movimiento.setProcessed(Boolean.TRUE);
            movimiento.setStatus(Boolean.TRUE);
            inventarioService.create(movimiento);
        }
        return codigo;
    }

    /** Articulo activo sin ningun movimiento, para los escenarios de precision sembrada a mano. */
    private Long sembrarArticuloSinVentas(String etiqueta) {
        return sembrarArticulo(etiqueta);
    }

    private Long sembrarArticulo(String etiqueta) {
        String sufijo = etiqueta + "-" + SUFIJOS.incrementAndGet();
        Articulos articulo = new Articulos();
        articulo.setNombre("IT Seleccion Metodo " + sufijo);
        articulo.setCodigoBarra("IT-SM-" + sufijo);
        articulo.setUnidadMedida("Unidad");
        articulo.setStatus(true);
        articulo.setProcessed(true);
        articulosService.create(articulo);
        assertNotNull(articulo.getCodigo(), "el alta del articulo debe asignar codigo");
        return articulo.getCodigo();
    }

    /** Los 60 dias sembrados, terminando hoy. */
    private static List<LocalDate> diasSembrados() {
        LocalDate hoy = LocalDate.now();
        List<LocalDate> dias = new ArrayList<>(DIAS_SEMBRADOS);
        for (int desplazamiento = DIAS_SEMBRADOS - 1; desplazamiento >= 0; desplazamiento--) {
            dias.add(hoy.minusDays(desplazamiento));
        }
        return dias;
    }

    private static double unidadesDelDia(LocalDate dia) {
        DayOfWeek diaSemana = dia.getDayOfWeek();
        return (diaSemana == DayOfWeek.SATURDAY || diaSemana == DayOfWeek.SUNDAY)
                ? VENTA_FIN_DE_SEMANA
                : VENTA_DIA_HABIL;
    }

    /**
     * La misma serie que el servicio debe reconstruir, construida en memoria desde las mismas
     * reglas que siembran los movimientos. Si las dos divergearan, el conteo de candidatos
     * esperados seria invalido.
     */
    private static List<SerieDiaria> serieEsperada() {
        List<SerieDiaria> serie = new ArrayList<>(DIAS_SEMBRADOS);
        for (LocalDate dia : diasSembrados()) {
            serie.add(new SerieDiaria(dia, unidadesDelDia(dia)));
        }
        return serie;
    }

    private Articulos buscarArticulo(Long codigo) {
        Articulos articulo = em.find(Articulos.class, codigo);
        assertNotNull(articulo, "el articulo " + codigo + " debe seguir existiendo");
        return articulo;
    }

    private void guardar(PrecisionPronostico... filas) {
        try {
            utx.begin();
            for (PrecisionPronostico fila : filas) {
                em.persist(fila);
            }
            utx.commit();
        } catch (Exception e) {
            try {
                utx.rollback();
            } catch (Exception rollback) {
                rollback.printStackTrace(); // best-effort cleanup; primary failure already reported
            }
            throw new IllegalStateException("No se pudieron sembrar las filas de precision", e);
        }
    }

    private PrecisionPronostico fila(Long codigoArticulo, String metodo, Double mase, Double sesgo) {
        PrecisionPronostico fila = new PrecisionPronostico();
        fila.setArticulo(buscarArticulo(codigoArticulo));
        fila.setMetodo(metodo);
        fila.setMase(mase);
        fila.setSesgo(sesgo);
        fila.setHorizonteDias(14);
        fila.setFechaCalculo(java.time.LocalDateTime.now());
        return fila;
    }

    private List<PrecisionPronostico> leerPrecision(Long codigoArticulo) {
        TypedQuery<PrecisionPronostico> consulta = em.createQuery(
                        "SELECT p FROM PrecisionPronostico p WHERE p.articulo.codigo = :codigo ORDER BY p.metodo",
                        PrecisionPronostico.class)
                .setParameter("codigo", codigoArticulo);
        return consulta.getResultList();
    }

    /**
     * Borra todo lo creado por el escenario: primero las filas de precision (referencian al
     * articulo), despues los movimientos de inventario y por ultimo el articulo. Reutilizar el
     * mismo patron de EntityManager+UserTransaction del resto de la suite.
     */
    private void limpiar(Long codigoArticulo) {
        if (codigoArticulo == null) {
            return;
        }
        try {
            utx.begin();
            em.createQuery("DELETE FROM PrecisionPronostico p WHERE p.articulo.codigo = :codigo")
                    .setParameter("codigo", codigoArticulo)
                    .executeUpdate();
            em.createQuery("DELETE FROM Inventario i WHERE i.articulo.codigo = :codigo")
                    .setParameter("codigo", codigoArticulo)
                    .executeUpdate();
            Articulos articulo = em.find(Articulos.class, codigoArticulo);
            if (articulo != null) {
                em.remove(articulo);
            }
            utx.commit();
        } catch (Exception e) {
            try {
                utx.rollback();
            } catch (Exception rollback) {
                rollback.printStackTrace(); // best-effort cleanup; primary failure already reported
            }
            throw new IllegalStateException("Limpieza de fixtures fallida", e);
        }
    }
}
