package Controllers.Api.App.Reportes;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.not;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import Models.Articulos.Articulos;
import Models.ComprobantesEmitidos;
import Models.Detalles.DetalleServicio;
import Models.Detalles.LineaDetalle;
import Models.Encabezado.Encabezado;
import Models.Inventario;
import Models.PrecisionPronostico;
import Services.ArticulosService;
import Services.ComprobantesEmitidosService;
import Services.InventarioService;
import Services.SeleccionMetodoService;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import jakarta.transaction.UserTransaction;
import org.junit.jupiter.api.Test;

/**
 * Exactitud del pronóstico en la página de Pronósticos: las columnas
 * <b>método elegido / MASE / sesgo</b> de {@link PronosticosResource} y su
 * marcador "sin evaluar".
 *
 * <p><b>Por qué el fixture necesita las dos fuentes de venta.</b> La fila de
 * la tabla la decide {@code StockForecastService.generateBulkForecast}, que
 * exige historial de ventas en {@code ComprobantesEmitidos} (línea cuyo
 * {@code detalle} es el nombre del artículo), mientras que el backtesting de
 * {@code SeleccionMetodoService} reconstruye la serie desde los movimientos
 * {@code Inventario} de tipo 'Venta'. En producción las dos existen para la
 * misma venta —el carrito descuenta stock y emite la factura—, así que el
 * fixture siembra ambas: sin comprobante el artículo no aparece en la tabla y
 * sin movimientos no hay nada que evaluar.</p>
 *
 * <p><b>Sin {@code @TestTransaction}.</b> Igual que en
 * {@code SeleccionMetodoTest}: los fixtures se COMITEAN por código porque el
 * servicio y el hilo HTTP que sirve la página viven fuera de la transacción
 * del test, y se borran en un {@code finally} con EntityManager +
 * UserTransaction. JUnit crea una instancia por método, así que la lista de
 * facturas sembradas es propia de cada escenario.</p>
 *
 * <p><b>Aislamiento.</b> Cada artículo lleva un sufijo único en nombre y
 * código de barra, de modo que su fila en el HTML se localiza sin ambigüedad.
 * El paginador se pide con {@code size=200} (tope de {@code Tablas.paginaDe})
 * para que la fila del fixture caiga en la primera página aunque otros
 * escenarios hayan dejado artículos de prueba en la base compartida.</p>
 */
@QuarkusTest
class PronosticosPrecisionTest {

    private static final String PAGE = "/app/reportes/inventario/pronosticos";

    /** Tope de página de {@code Tablas.paginaDe}: mete todas las filas en una. */
    private static final String TAMANO_PAGINA = "200";

    /** Días de historia sembrados: dos ciclos semanales largos. */
    private static final int DIAS_SEMBRADOS = 60;

    private static final long VENTA_DIA_HABIL = 2L;
    private static final long VENTA_FIN_DE_SEMANA = 8L;

    private static final AtomicInteger SUFIJOS = new AtomicInteger();

    /** Ids de lo sembrado por el escenario en curso, para borrarlo por id. */
    private final List<Factura> facturas = new ArrayList<>();

    @Inject
    SeleccionMetodoService seleccionMetodoService;

    @Inject
    ArticulosService articulosService;

    @Inject
    InventarioService inventarioService;

    @Inject
    ComprobantesEmitidosService emitidosService;

    @Inject
    EntityManager em;

    @Inject
    UserTransaction utx;

    // ── escenarios ──────────────────────────────────────────────────────

    /**
     * Camino real: se siembra un artículo con ventas, se llama a
     * {@code evaluarArticulo} y la fila de la página lleva el método elegido
     * traducido al español, el MASE a dos decimales y el sesgo con su lectura
     * en lenguaje llano.
     *
     * <p>Las tres superficies que muestran pronóstico por artículo
     * ({@code pronosticos}, {@code reorden} y el fragmento HTMX) se comprueban
     * con el mismo fixture: las columnas están donde se pinta el pronóstico,
     * no solo en la vista por defecto.</p>
     */
    @Test
    @TestSecurity(user = "admin", roles = {"admin", "inventario"})
    void articuloEvaluadoMuestraMetodoMaseYSesgo() {
        ArticuloSembrado articulo = null;
        try {
            articulo = sembrarArticuloConVentas("IT Prec Evaluado");
            sembrarComprobante(articulo.nombre(), 5);

            int guardadas = seleccionMetodoService.evaluarArticulo(articulo.codigo(), 14, 3);
            assertTrue(guardadas > 0,
                    "el fixture debe producir filas de precision; con 0 la prueba no probaria nada");

            String elegido = seleccionMetodoService.metodoElegido(articulo.codigo()).orElse(null);
            assertNotNull(elegido, "con la serie semanal sembrada debe haber metodo elegido");
            PrecisionPronostico fila = leerPrecision(articulo.codigo()).stream()
                    .filter(precision -> elegido.equals(precision.getMetodo()))
                    .findFirst()
                    .orElse(null);
            assertNotNull(fila, "debe existir la fila del metodo elegido " + elegido);

            String esperadoMetodo = nombreEnEspanol(elegido);
            String esperadoMase = dosDecimales(fila.getMase());
            String esperadoSesgo = sesgoLegible(fila.getSesgo());

            String filaPronosticos = filaDe(pagina(), articulo.nombre());
            assertThat(filaPronosticos).contains("<td>" + esperadoMetodo + "</td>");
            assertThat(filaPronosticos).contains("<td>" + esperadoMase + "</td>");
            assertThat(filaPronosticos).contains("<td>" + esperadoSesgo + "</td>");
            assertThat(filaPronosticos).doesNotContain("sin evaluar");

            String filaReorden = filaDe(pagina("reorden"), articulo.nombre());
            assertThat(filaReorden).contains("<td>" + esperadoMetodo + "</td>");
            assertThat(filaReorden).contains("<td>" + esperadoSesgo + "</td>");

            String fragmento = fragmentoHx();
            assertThat(fragmento).contains("data-kit-table");
            assertThat(filaDe(fragmento, articulo.nombre()))
                    .contains("<td>" + esperadoMetodo + "</td>");
        } finally {
            limpiar(articulo);
        }
    }

    /**
     * Un artículo que aparece en el pronóstico pero nunca se evaluó se muestra
     * con los marcadores "sin evaluar" y "-": ni celdas vacías ni un error, la
     * página sigue respondiendo 200.
     */
    @Test
    @TestSecurity(user = "admin", roles = {"admin", "inventario"})
    void articuloSinEvaluarMuestraSinEvaluar() {
        ArticuloSembrado articulo = null;
        try {
            articulo = sembrarArticulo("IT Prec Sin Evaluar");
            sembrarComprobante(articulo.nombre(), 5);

            assertTrue(seleccionMetodoService.metodoElegido(articulo.codigo()).isEmpty(),
                    "el fixture no debe tener metodo elegido");
            assertThat(leerPrecision(articulo.codigo())).isEmpty();

            String fila = filaDe(pagina(), articulo.nombre());
            assertThat(fila).contains("<td>sin evaluar</td>");
            assertThat(fila).contains("<td>-</td>");
        } finally {
            limpiar(articulo);
        }
    }

    /**
     * El corte del sesgo (+1 / -1) se muestra con el texto que corresponde, y
     * una fila con MASE indefinido (serie plana) cae en "sin evaluar" aunque
     * tenga filas: sin MASE definido no existe elección que mostrar.
     *
     * <p>Los valores se siembran a mano para fijar los umbrales documentados en
     * lugar de confiar en lo que el motor produzca para una serie dada.</p>
     */
    @Test
    @TestSecurity(user = "admin", roles = {"admin", "inventario"})
    void calificadoresDelSesgoSiguenElSignoYElUmbral() {
        ArticuloSembrado subestimar = null;
        ArticuloSembrado sobrestimar = null;
        ArticuloSembrado calibrado = null;
        ArticuloSembrado indefinido = null;
        try {
            subestimar = sembrarArticulo("IT Prec Sesgo Sub");
            sobrestimar = sembrarArticulo("IT Prec Sesgo Sob");
            calibrado = sembrarArticulo("IT Prec Sesgo Cal");
            indefinido = sembrarArticulo("IT Prec Sesgo Ind");
            for (ArticuloSembrado articulo : List.of(subestimar, sobrestimar, calibrado,
                    indefinido)) {
                sembrarComprobante(articulo.nombre(), 5);
            }

            guardar(
                    filaPrecision(subestimar.codigo(), "MEDIA_MOVIL", 0.83, 1.5d),
                    filaPrecision(sobrestimar.codigo(), "SES", 0.9, -2.25d),
                    filaPrecision(calibrado.codigo(), "SBA", 1.1, 0.4d),
                    filaPrecision(indefinido.codigo(), "INGENUO", null, null));

            String html = pagina();
            assertThat(filaDe(html, subestimar.nombre()))
                    .contains("<td>Media móvil</td>")
                    .contains("<td>0.83</td>")
                    .contains("<td>1.50 · tiende a subestimar</td>");
            assertThat(filaDe(html, sobrestimar.nombre()))
                    .contains("<td>Suavizado exponencial</td>")
                    .contains("<td>0.90</td>")
                    .contains("<td>-2.25 · tiende a sobrestimar</td>");
            assertThat(filaDe(html, calibrado.nombre()))
                    .contains("<td>SBA (intermitente)</td>")
                    .contains("<td>1.10</td>")
                    .contains("<td>0.40 · calibrado</td>");
            assertThat(filaDe(html, indefinido.nombre())).contains("<td>sin evaluar</td>");
        } finally {
            limpiar(subestimar);
            limpiar(sobrestimar);
            limpiar(calibrado);
            limpiar(indefinido);
        }
    }

    /**
     * Las tres columnas de exactitud se emiten como columnas no ordenables del
     * kit: encabezado presente y sin enlace de orden, porque las celdas llegan
     * ya formateadas como texto y ordenarlas alfabéticamente no significaría
     * nada.
     */
    @Test
    @TestSecurity(user = "admin", roles = {"admin", "inventario"})
    void columnasDeExactitudNoSonOrdenables() {
        given()
                .when().get(PAGE)
                .then()
                .statusCode(200)
                .body(containsString("Método"))
                .body(containsString(">MASE</th>"))
                .body(not(containsString("data-kit-sort=\"mase\"")));
    }

    // ── lectura del HTML ────────────────────────────────────────────────

    private static String pagina() {
        return pagina(null);
    }

    private static String pagina(String seccion) {
        RequestSpecification peticion = given();
        if (seccion != null) {
            peticion = peticion.queryParam("seccion", seccion);
        }
        return peticion.queryParam("size", TAMANO_PAGINA)
                .when().get(PAGE)
                .then()
                .statusCode(200)
                .extract().asString();
    }

    private static String fragmentoHx() {
        return given()
                .header("HX-Request", "true")
                .queryParam("size", TAMANO_PAGINA)
                .when().get(PAGE)
                .then()
                .statusCode(200)
                .extract().asString();
    }

    /**
     * El {@code <tr>} que contiene el nombre del artículo.
     *
     * <p>Se recorta por fila y no se busca en el HTML entero porque la página
     * lista los artículos de todos los escenarios sembrados: sin recortar, el
     * "calibrado" de otro artículo daría un falso positivo.</p>
     */
    private static String filaDe(String html, String nombreArticulo) {
        for (String trozo : html.split("<tr")) {
            if (trozo.contains(nombreArticulo)) {
                return trozo;
            }
        }
        return fail("no se encontro la fila del articulo '" + nombreArticulo
                + "' en la tabla del reporte de pronosticos. Filas renderizadas: "
                + filasRenderizadas(html));
    }

    /** Las filas de la tabla tal como salieron, para leer el fallo de un vistazo. */
    private static String filasRenderizadas(String html) {
        List<String> filas = new ArrayList<>();
        for (String trozo : html.split("<tr")) {
            String texto = trozo.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
            if (!texto.isEmpty()) {
                filas.add(texto);
            }
        }
        return String.valueOf(filas);
    }

    // ── fixtures ────────────────────────────────────────────────────────

    /** Artículo + 60 días de ventas: lo que el backtesting necesita para medir. */
    private ArticuloSembrado sembrarArticuloConVentas(String etiqueta) {
        ArticuloSembrado articulo = sembrarArticulo(etiqueta);
        for (LocalDate dia : diasSembrados()) {
            Inventario movimiento = new Inventario();
            movimiento.setArticulo(buscarArticulo(articulo.codigo()));
            // CarritoService descuenta stock: la venta se guarda en negativo.
            movimiento.setCantidad(BigDecimal.valueOf(-unidadesDelDia(dia)));
            movimiento.setTipoMovimiento("Venta");
            movimiento.setFechaMovimiento(Date.from(
                    dia.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant()));
            movimiento.setNotas("IT Pronosticos Precision " + etiqueta);
            movimiento.setProcessed(Boolean.TRUE);
            movimiento.setStatus(Boolean.TRUE);
            inventarioService.create(movimiento);
        }
        return articulo;
    }

    private ArticuloSembrado sembrarArticulo(String etiqueta) {
        String sufijo = "-s" + SUFIJOS.incrementAndGet();
        Articulos articulo = new Articulos();
        articulo.setNombre(etiqueta + sufijo);
        articulo.setCodigoBarra("IT-PP" + sufijo);
        articulo.setUnidadMedida("Unidad");
        articulo.setStatus(true);
        articulo.setProcessed(true);
        articulosService.create(articulo);
        assertNotNull(articulo.getCodigo(), "el alta del articulo debe asignar codigo");
        return new ArticuloSembrado(articulo.getCodigo(), articulo.getNombre());
    }

    /**
     * Código y nombre tal como quedaron guardados. El nombre importa: la línea
     * de detalle se enlaza por texto, no por id.
     */
    private record ArticuloSembrado(Long codigo, String nombre) {
    }

    /**
     * Factura de una línea por el artículo: es lo que
     * {@code StockForecastService.getSalesHistory} lee para considerarlo con
     * historial de ventas (agrupa por {@code lineaDetalle.detalle}, que debe
     * coincidir exactamente con el nombre del artículo, con sufijo incluido).
     */
    private void sembrarComprobante(String nombreArticulo, int unidades) {
        Encabezado encabezado = new Encabezado();
        encabezado.setNumeroConsecutivo("IT-PP-s" + SUFIJOS.incrementAndGet());
        encabezado.setFechaEmision(LocalDateTime.now().minusDays(1));
        encabezado.setCondicionVenta("01");
        encabezado.setSchemaVersion("4.4");
        encabezado.setCodigoDocumento("01");

        LineaDetalle linea = new LineaDetalle();
        linea.setNumeroLinea(1);
        linea.setCantidad(BigDecimal.valueOf(unidades));
        linea.setPrecioUnitario(BigDecimal.valueOf(1000));
        linea.setDetalle(nombreArticulo);

        DetalleServicio detalles = new DetalleServicio();
        // Los dos lados: la FK vive en la linea (mappedBy), asi que con solo la
        // coleccion el guardado deja detalle_servicio_id en null y el articulo
        // no tendria historial de ventas.
        linea.setDetalleServicio(detalles);
        detalles.setLineasDetalle(new ArrayList<>(List.of(linea)));

        ComprobantesEmitidos comprobante = new ComprobantesEmitidos();
        comprobante.setSchemaVersion("4.4");
        comprobante.setEncabezado(encabezado);
        comprobante.setDetalles(detalles);
        comprobante.setStatus(true);
        comprobante.setUser("it-pronosticos-precision");
        comprobante.setHaciendaEstado("ACEPTADO");
        ComprobantesEmitidos creado = emitidosService.createAndReturn(comprobante);
        assertNotNull(creado, "el comprobante del fixture debe crearse: sin el, el articulo "
                + "no tiene historial de ventas y no aparece en la tabla");
        facturas.add(new Factura(creado.getId(), detalles.getId(), encabezado.getId(),
                nombreArticulo));
    }

    /** Lo mínimo que identifica una factura sembrada para borrarla por id. */
    private record Factura(Long comprobante, Long detalle, Long encabezado, String linea) {
    }

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

    private PrecisionPronostico filaPrecision(Long codigoArticulo, String metodo,
                                              Double mase, Double sesgo) {
        PrecisionPronostico fila = new PrecisionPronostico();
        fila.setArticulo(buscarArticulo(codigoArticulo));
        fila.setMetodo(metodo);
        fila.setMase(mase);
        fila.setSesgo(sesgo);
        fila.setHorizonteDias(14);
        fila.setFechaCalculo(LocalDateTime.now());
        return fila;
    }

    private List<PrecisionPronostico> leerPrecision(Long codigoArticulo) {
        TypedQuery<PrecisionPronostico> consulta = em.createQuery(
                        "SELECT p FROM PrecisionPronostico p "
                                + "WHERE p.articulo.codigo = :codigo ORDER BY p.metodo",
                        PrecisionPronostico.class)
                .setParameter("codigo", codigoArticulo);
        return consulta.getResultList();
    }

    /**
     * Borra lo sembrado por el escenario: las facturas por id —línea,
     * comprobante, detalle y encabezado, en el orden que imponen las claves
     * foráneas y sin depender de la cascada— y para el artículo sus filas de
     * precisión, sus movimientos y el artículo mismo, al que apuntan. Es
     * idempotente: cada escenario puede llamar a limpiar más de una vez desde
     * su finally.
     */
    private void limpiar(ArticuloSembrado articulo) {
        try {
            utx.begin();
            for (Factura factura : facturas) {
                // Orden impuesto por las FK: la linea cuelga del detalle, el
                // comprobante apunta al detalle (comprobantesemitidos
                // .detalle_servicio_id) y el encabezado es del comprobante.
                em.createQuery("DELETE FROM LineaDetalle l WHERE l.detalle = :detalle")
                        .setParameter("detalle", factura.linea())
                        .executeUpdate();
                em.createQuery("DELETE FROM ComprobantesEmitidos c WHERE c.id = :id")
                        .setParameter("id", factura.comprobante())
                        .executeUpdate();
                em.createQuery("DELETE FROM DetalleServicio d WHERE d.id = :id")
                        .setParameter("id", factura.detalle())
                        .executeUpdate();
                em.createQuery("DELETE FROM Encabezado e WHERE e.id = :id")
                        .setParameter("id", factura.encabezado())
                        .executeUpdate();
            }
            facturas.clear();
            if (articulo != null) {
                em.createQuery("DELETE FROM PrecisionPronostico p WHERE p.articulo.codigo = :codigo")
                        .setParameter("codigo", articulo.codigo())
                        .executeUpdate();
                em.createQuery("DELETE FROM Inventario i WHERE i.articulo.codigo = :codigo")
                        .setParameter("codigo", articulo.codigo())
                        .executeUpdate();
                Articulos entity = em.find(Articulos.class, articulo.codigo());
                if (entity != null) {
                    em.remove(entity);
                }
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

    // ── expectativas de formato ─────────────────────────────────────────

    /**
     * Traducción de los nombres de {@code MetodoPronostico} replicada desde la
     * página. Se reimplementa aquí a propósito: si la prueba llamara al mismo
     * método que el resource, un renombrado movería los dos a la vez y la
     * prueba no detectaría nada.
     */
    private static String nombreEnEspanol(String metodo) {
        return switch (metodo) {
            case "INGENUO" -> "Ingenuo";
            case "ESTACIONAL_INGENUO" -> "Estacional ingenuo";
            case "MEDIA_MOVIL" -> "Media móvil";
            case "SES" -> "Suavizado exponencial";
            case "HOLT_AMORTIGUADO" -> "Holt amortiguado";
            case "HOLT_WINTERS" -> "Holt-Winters";
            case "SBA" -> "SBA (intermitente)";
            default -> metodo;
        };
    }

    private static String dosDecimales(Double valor) {
        if (valor == null) {
            return "-";
        }
        return BigDecimal.valueOf(valor).setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private static String sesgoLegible(Double sesgo) {
        if (sesgo == null) {
            return "-";
        }
        String calificador;
        if (sesgo > 1.0d) {
            calificador = "tiende a subestimar";
        } else if (sesgo < -1.0d) {
            calificador = "tiende a sobrestimar";
        } else {
            calificador = "calibrado";
        }
        return dosDecimales(sesgo) + " · " + calificador;
    }
}
