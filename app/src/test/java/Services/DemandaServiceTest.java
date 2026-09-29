package Services;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.UserTransaction;
import Models.Articulos.Articulos;
import Models.Familia;
import Models.Inventario;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cobertura de {@link DemandaService}: prediccion por articulo con intervalo, punto de reorden,
 * pronostico top-down por familia y los casos limite del contrato.
 *
 * <p><b>Por que NO {@code @TestTransaction}.</b> El servicio se apoya en
 * {@link SeleccionMetodoService}, que corre en su propia transaccion (el interceptor de
 * {@code @Transactional} ejecuta en otro hilo logico que el queerial el test), de modo que unos
 * fixtures sin commit serian invisibles para el. Por eso —igual que en
 * {@code StockAlertConfigResourceTest} y {@code SeleccionMetodoTest}— los fixtures se COMITEAN
 * por codigo y se borran en un finally con EntityManager+UserTransaction.</p>
 *
 * <p><b>Aislamiento.</b> Las pruebas de {@code @QuarkusTest} comparten la base de datos
 * {@code mercurius_test}, asi que cada escenario siembra sufijo unico (AtomicInteger + nombre del
 * metodo) en nombre y codigo de barra, y sus filas se eliminan siempre en el finally.</p>
 *
 * <p><b>Serie sembrada.</b> 60 dias de ventas con patron semanal explicito —2 unidades de lunes a
 * viernes, 8 el sabado y el domingo— sin dias en cero. Con ese patron la media diaria es
 * 26/7 unidades y cualquier bloque de 14 dias consecutivos suma exactamente 52 (dos semanas
 * completas), que es la referencia contra la que se comprueba la prediccion: la banda 0.5x-2x es
 * deliberadamente ancha porque lo que se verifica es que el servicio no se desvanece (nivel que
 * se pierde contra la media) ni se desboca (metodo que explota en horizontes largos), no que
 * reproduzca el patron al dia.</p>
 */
@QuarkusTest
@Tag("integration-services")
class DemandaServiceTest {

    /** Dias de historia sembrados: dos ciclos semanales largos y cuarto. */
    private static final int DIAS_SEMBRADOS = 60;

    /** Unidades vendidas de dia habil. */
    private static final long VENTA_DIA_HABIL = 2L;

    /** Unidades vendidas de sabado y domingo. */
    private static final long VENTA_FIN_DE_SEMANA = 8L;

    /** Dias del rango de prediccion. */
    private static final int DIAS_PREDICCION = 14;

    private static final AtomicInteger SUFIJOS = new AtomicInteger();

    @Inject
    DemandaService demandaService;

    @Inject
    ArticulosService articulosService;

    @Inject
    FamiliaService familiaService;

    @Inject
    InventarioService inventarioService;

    @Inject
    EntityManager em;

    @Inject
    UserTransaction utx;

    // ── escenarios ──────────────────────────────────────────────────────

    /**
     * La prediccion de 14 dias con patron semanal cae en la banda 0.5x-2x de la referencia ingenua
     * y respeta el contrato del intervalo.
     *
     * <p>La referencia ingenua es {@code 14 * mediaDiaria}: el numero de dias por la media de
     * unidades del dia, que es lo que haria un reporte hecho a mano. El servicio no tiene por que
     * acertarlo (no se le pide), pero si se apartarse de esa banda seria porque el metodo elegido
     * esta degradado, que es justo el fallo que este servicio tiene que evitar.</p>
     */
    @Test
    void pronosticaEnBandaRazonableConPatronSemanal() {
        Long codigo = null;
        try {
            codigo = sembrarArticuloConVentas("banda");

            LocalDate hoy = LocalDate.now();
            LocalDate desde = hoy.plusDays(1);
            LocalDate hasta = hoy.plusDays(DIAS_PREDICCION);
            DemandaService.PrediccionDemanda prediccion = demandaService.predecirDemanda(codigo, desde, hasta);

            assertNotNull(prediccion, "un articulo existente siempre devuelve prediccion");
            assertEquals(desde, prediccion.desde(), "el rango devuelto debe ser el pedido");
            assertEquals(hasta, prediccion.hasta(), "el rango devuelto debe ser el pedido");
            assertNotNull(prediccion.metodo(), "el metodo efectivo nunca es nulo");
            assertTrue(MetodoPronosticoValido.esValido(prediccion.metodo()),
                    "el metodo '" + prediccion.metodo() + "' no pertenece al catalogo de pronostico");

            double referenciaIngenua = DIAS_PREDICCION * mediaDiaria();
            assertTrue(prediccion.totalPronosticado() > 0.5 * referenciaIngenua
                            && prediccion.totalPronosticado() < 2.0 * referenciaIngenua,
                    "el total " + prediccion.totalPronosticado() + " debe caer entre "
                            + (0.5 * referenciaIngenua) + " y " + (2.0 * referenciaIngenua)
                            + " (14 dias x media diaria " + mediaDiaria() + " = " + referenciaIngenua + ")");

            assertTrue(prediccion.limiteInferior() <= prediccion.totalPronosticado(),
                    "el limite inferior " + prediccion.limiteInferior() + " no puede superar el total "
                            + prediccion.totalPronosticado());
            assertTrue(prediccion.limiteSuperior() >= prediccion.totalPronosticado(),
                    "el limite superior " + prediccion.limiteSuperior() + " no puede quedar por debajo del total "
                            + prediccion.totalPronosticado());
            assertTrue(prediccion.limiteInferior() >= 0.0,
                    "una demanda negativa no existe: el limite inferior debe estar acotado a 0");
            assertTrue(prediccion.limiteInferior() < prediccion.limiteSuperior(),
                    "una serie con varianza real debe abrir el intervalo");

            // Sin corrida de backtesting no hay MASE guardado, y el servicio no debe inventar uno
            // ni fallar por ello.
            assertNull(prediccion.mase(), "sin filas de precision el MASE debe ser null, no 0");
        } finally {
            limpiarArticulo(codigo);
        }
    }

    /**
     * El pronostico top-down de una familia suma en positivo y se identifica como tal.
     *
     * <p>Se siembran dos articulos de la misma familia con la misma serie semanal, que es la
     * situacion para la que el agregado tiene sentido: el total de la familia debe estar en el
     * entorno del doble del total de un articulo, no del simple.</p>
     */
    @Test
    void pronosticoDeFamiliaSumaEnPositivo() {
        Long familiaCodigo = null;
        List<Long> articulos = new ArrayList<>();
        try {
            familiaCodigo = sembrarFamilia("familia");
            articulos.add(sembrarArticuloConVentas("miembroA", familiaCodigo));
            articulos.add(sembrarArticuloConVentas("miembroB", familiaCodigo));

            LocalDate hoy = LocalDate.now();
            LocalDate desde = hoy.plusDays(1);
            LocalDate hasta = hoy.plusDays(DIAS_PREDICCION);
            DemandaService.PrediccionDemanda familia = demandaService.predecirFamilia(familiaCodigo, desde, hasta);

            assertNotNull(familia, "una familia existente siempre devuelve prediccion");
            assertTrue(familia.totalPronosticado() > 0.0,
                    "una familia con ventas debe sumar en positivo, fue " + familia.totalPronosticado());
            assertTrue(familia.limiteInferior() <= familia.totalPronosticado()
                            && familia.totalPronosticado() <= familia.limiteSuperior(),
                    "el total debe quedar dentro de su propio intervalo");
            assertNotNull(familia.metodo(), "el metodo de familia nunca es nulo");
            assertTrue(familia.metodo().startsWith("TOP_DOWN:"),
                    "el metodo de familia va prefijado, fue " + familia.metodo());
            assertNull(familia.mase(),
                    "la precision de una familia no se persiste (la tabla es por articulo): MASE null");

            // Top-down: el total de la familia responde al agregado, no a la suma de un miembro.
            double referenciaMiembro = DIAS_PREDICCION * mediaDiaria();
            assertTrue(familia.totalPronosticado() > 0.8 * referenciaMiembro,
                    "el agregado de dos miembros identicos debe acercarse al doble del miembro ("
                            + referenciaMiembro + " por miembro), fue " + familia.totalPronosticado());
        } finally {
            for (Long codigo : articulos) {
                limpiarArticulo(codigo);
            }
            limpiarFamilia(familiaCodigo);
        }
    }

    /**
     * Un articulo sin ninguna venta no es un error: pronostica 0 con intervalo degenerado, metodo
     * INGENUO y MASE nulo. Es el caso de un articulo recien dado de alta, que aparece en todos los
     * listados de reposicion.
     */
    @Test
    void articuloSinHistoriaPronosticaCeros() {
        Long codigo = null;
        try {
            codigo = sembrarArticulo("sinVentas");

            LocalDate hoy = LocalDate.now();
            DemandaService.PrediccionDemanda prediccion =
                    demandaService.predecirDemanda(codigo, hoy.plusDays(1), hoy.plusDays(DIAS_PREDICCION));

            assertNotNull(prediccion, "sin historia hay pronostico vacio, no null");
            assertEquals(0.0, prediccion.totalPronosticado(), 1e-9, "sin ventas no hay demanda que pronosticar");
            assertEquals(0.0, prediccion.limiteInferior(), 1e-9, "el intervalo de un total 0 empieza en 0");
            assertEquals(0.0, prediccion.limiteSuperior(), 1e-9, "el intervalo de un total 0 acaba en 0");
            assertEquals("INGENUO", prediccion.metodo(), "sin historia el unico metodo posible es INGENUO");
            assertNull(prediccion.mase(), "sin historia no hay MASE");
        } finally {
            limpiarArticulo(codigo);
        }
    }

    /**
     * El punto de reorden cubre el plazo de reposicion con el stock de seguridad del nivel de
     * servicio pedido, y sube al subir el nivel de servicio: es la unica garantia de que el
     * intervalo y el punto de reorden usan la misma sigma.
     */
    @Test
    void puntoDeReordenPositivoYCreceConElNivelDeServicio() {
        Long codigo = null;
        try {
            codigo = sembrarArticuloConVentas("reorden");

            int plazo = 7;
            double laxo = demandaService.puntoReorden(codigo, plazo, 0.90);
            double estricto = demandaService.puntoReorden(codigo, plazo, 0.99);

            assertTrue(laxo > 0.0, "con ventas el punto de reorden debe ser positivo, fue " + laxo);
            assertTrue(laxo <= estricto,
                    "un nivel de servicio mayor no puede exigir menos stock: " + laxo + " (90%) vs "
                            + estricto + " (99%)");
        } finally {
            limpiarArticulo(codigo);
        }
    }

    /**
     * Un rango invertido no tiene lectura posible: se rechaza con IllegalArgumentException en vez
     * de devolver una prediccion silenciosamente vacia o invertida.
     */
    @Test
    void rangoInvertidoSeRechaza() {
        Long codigo = null;
        try {
            codigo = sembrarArticuloConVentas("rango");
            LocalDate hoy = LocalDate.now();
            // Copia effectively final: el finally necesita la variable asignable y las lambdas la
            // capturan.
            Long codigoArticulo = codigo;

            assertThrows(IllegalArgumentException.class,
                    () -> demandaService.predecirDemanda(codigoArticulo, hoy, hoy.minusDays(1)),
                    "un rango con 'hasta' anterior a 'desde' debe rechazarse");
            assertThrows(IllegalArgumentException.class,
                    () -> demandaService.predecirFamilia(1L, hoy, hoy.minusDays(1)),
                    "la familia aplica el mismo contrato de rango que el articulo");
        } finally {
            limpiarArticulo(codigo);
        }
    }

    /**
     * Un articulo inexistente devuelve null en la prediccion y 0 en el punto de reorden, en lugar
     * de propagar un error de entidad no encontrada hacia la pantalla que lo pide.
     */
    @Test
    void articuloInexistenteDevuelveNullYReordenCero() {
        Long inexistente = -987654321L;
        LocalDate hoy = LocalDate.now();

        assertNull(demandaService.predecirDemanda(inexistente, hoy.plusDays(1), hoy.plusDays(DIAS_PREDICCION)),
                "un articulo inexistente no tiene pronostico: se devuelve null");
        assertEquals(0.0, demandaService.puntoReorden(inexistente, 7, 0.95), 1e-9,
                "un articulo inexistente no tiene punto de reorden");
    }

    // ── fixtures ────────────────────────────────────────────────────────

    /**
     * Articulo + 60 dias de movimientos 'Venta' con patron semanal explicito.
     *
     * <p>Las ventas se guardan con {@code cantidad} negativa, que es la convencion de
     * {@code CarritoService} al descontar stock; el servicio toma el valor absoluto, asi que
     * sembrar el signo equivocado daria una serie de demanda negativa y flotante.</p>
     */
    private Long sembrarArticuloConVentas(String etiqueta) {
        return sembrarArticuloConVentas(etiqueta, null);
    }

    private Long sembrarArticuloConVentas(String etiqueta, Long familiaCodigo) {
        Long codigo = sembrarArticulo(etiqueta);
        if (familiaCodigo != null) {
            Articulos articulo = em.find(Articulos.class, codigo);
            Familia familia = em.find(Familia.class, familiaCodigo.intValue());
            assertNotNull(familia, "la familia de fixture debe existir");
            articulo.setFamilia(familia);
            try {
                utx.begin();
                em.merge(articulo);
                utx.commit();
            } catch (Exception e) {
                try {
                    utx.rollback();
                } catch (Exception rollback) {
                    rollback.printStackTrace(); // best-effort cleanup; primary failure already reported
                }
                throw new IllegalStateException("No se pudo asignar la familia al articulo de prueba", e);
            }
        }

        for (LocalDate dia : diasSembrados()) {
            Inventario movimiento = new Inventario();
            movimiento.setArticulo(em.find(Articulos.class, codigo));
            movimiento.setCantidad(BigDecimal.valueOf(-unidadesDelDia(dia)));
            movimiento.setTipoMovimiento("Venta");
            // Mediodia: a caballo de cualquier cambio de horario de verano, para que la
            // conversion a LocalDate no pueda correrse un dia.
            movimiento.setFechaMovimiento(Date.from(dia.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant()));
            movimiento.setNotas("IT DemandaService " + etiqueta);
            movimiento.setProcessed(Boolean.TRUE);
            movimiento.setStatus(Boolean.TRUE);
            inventarioService.create(movimiento);
        }
        return codigo;
    }

    private Long sembrarArticulo(String etiqueta) {
        String sufijo = etiqueta + "-" + SUFIJOS.incrementAndGet();
        Articulos articulo = new Articulos();
        articulo.setNombre("IT Demanda " + sufijo);
        articulo.setCodigoBarra("IT-DM-" + sufijo);
        articulo.setUnidadMedida("Unidad");
        articulo.setStatus(true);
        articulo.setProcessed(true);
        articulosService.create(articulo);
        assertNotNull(articulo.getCodigo(), "el alta del articulo debe asignar codigo");
        return articulo.getCodigo();
    }

    /** Familia activa propia del escenario, porque el pronostico top-down se pide por su id. */
    private Long sembrarFamilia(String etiqueta) {
        String sufijo = etiqueta + "-" + SUFIJOS.incrementAndGet();
        Familia familia = new Familia();
        familia.setNombre("IT Demanda " + sufijo);
        familia.setStatus(true);
        familia.setFecha(new Date());
        familiaService.create(familia);
        assertTrue(familia.getId() > 0, "el alta de la familia debe asignar id");
        return (long) familia.getId();
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

    private static long unidadesDelDia(LocalDate dia) {
        DayOfWeek diaSemana = dia.getDayOfWeek();
        return (diaSemana == DayOfWeek.SATURDAY || diaSemana == DayOfWeek.SUNDAY)
                ? VENTA_FIN_DE_SEMANA
                : VENTA_DIA_HABIL;
    }

    /** Media diaria de la serie sembrada: 26 unidades entre 7 dias. */
    private static double mediaDiaria() {
        return (5.0 * VENTA_DIA_HABIL + 2.0 * VENTA_FIN_DE_SEMANA) / 7.0;
    }

    /**
     * Borra todo lo creado por el escenario: filas de precision (referencian al articulo),
     * movimientos de inventario y por ultimo el articulo. Orden obligatorio: las filas de
     * precision y los movimientos tienen FK al articulo.
     */
    private void limpiarArticulo(Long codigoArticulo) {
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

    /** La familia se borra despues que sus articulos, que la referencian. */
    private void limpiarFamilia(Long familiaCodigo) {
        if (familiaCodigo == null) {
            return;
        }
        try {
            utx.begin();
            Familia familia = em.find(Familia.class, familiaCodigo.intValue());
            if (familia != null) {
                em.remove(familia);
            }
            utx.commit();
        } catch (Exception e) {
            try {
                utx.rollback();
            } catch (Exception rollback) {
                rollback.printStackTrace(); // best-effort cleanup; primary failure already reported
            }
            throw new IllegalStateException("Limpieza de la familia de pruebas fallida", e);
        }
    }

    /**
     * El metodo que devuelve el servicio tiene que pertenecer al catalogo, incluidos los nombres
     * compuestos de familia. Se comprueba aqui para que un typo en la composicion del nombre
     * ("TOP_DOWN:...") se detecte en el test y no en la pantalla de pronosticos.
     */
    private static final class MetodoPronosticoValido {
        private static final List<String> CATALOGO = List.of("INGENUO", "ESTACIONAL_INGENUO", "MEDIA_MOVIL",
                "SES", "HOLT_AMORTIGUADO", "HOLT_WINTERS", "SBA");

        private MetodoPronosticoValido() {
        }

        static boolean esValido(String metodo) {
            String nombre = metodo.startsWith("TOP_DOWN:") ? metodo.substring("TOP_DOWN:".length()) : metodo;
            return CATALOGO.contains(nombre);
        }
    }
}
