package Controllers.Api.App;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.hamcrest.Matchers.equalTo;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

import Models.Articulos.ArticuloPrecio;
import Models.Articulos.Articulos;
import Models.ComprobantesRecibidos;
import Models.Departamento;
import Models.Detalles.CodigoComercial;
import Models.Detalles.DetalleServicio;
import Models.Detalles.LineaDetalle;
import Models.Encabezado.Emisor;
import Models.Encabezado.Encabezado;
import Models.Inventario;
import Models.Resumen.ResumenFactura;
import Models.Usuarios;
import Services.ArticulosService;
import Services.ComprobantesRecibidosService;
import Services.DepartamentoService;
import Services.Facturas.LineaDetalleService;
import Services.InventarioService;
import Services.LoginService;
import Utils.Parsers.Parser;

import org.jboss.logging.Logger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Caracterización de {@code FacturasRecibidasResource.procesarArticulos} tras
 * sustituir las lecturas POR LÍNEA ({@code findByName} / {@code findByBarCode} /
 * el SELECT de {@code createIfNotExist}) por una carga masiva previa con
 * {@code WHERE … IN} y tres mapas que sirven el bucle.
 *
 * <p><b>Qué prueba y qué no.</b> El cambio es una optimización de I/O, así que lo
 * que debe quedar intacto es el ESTADO FINAL persistido. No hay infraestructura de
 * conteo de consultas en la suite, por lo que NO se afirma sobre el número de
 * consultas: se afirma sobre las filas resultantes (artículos, precios,
 * movimientos de inventario, departamento y la bandera processed) y, en paralelo,
 * contra una réplica LITERAL del bucle anterior al cambio
 * ({@link #procesarArticulosLegacy}, una lectura por línea) que actúa como oráculo
 * de caracterización. La reducción N→3 se documenta por inspección del código, no
 * aquí.</p>
 *
 * <p><b>Escenarios:</b> (1) factura de siete líneas que ejercita nombre existente
 * resuelto por código de barra (con un comercial tipo "01" que debe ignorarse),
 * nombre nuevo sin código (crea), nombre nuevo REPETIDO (actualiza el artículo que
 * acaba de crear en vez de duplicarlo), nombre existente sin código (actualiza),
 * código nuevo (crea) y su repetido (actualiza), y una línea cuyo nombre no existe
 * en ninguna parte pero cuyo código SÍ pertenece a un artículo sembrado (manda el
 * código: actualiza ese artículo y el nombre de la línea nunca llega a existir) —
 * comparada contra el oráculo y contra el conjunto exacto esperado; (2) línea con
 * un comercial tipo "03" sin código: el NPE de {@code codigoBarra.isEmpty()} se
 * conserva y la factura no se marca procesada.</p>
 *
 * <p><b>Las líneas repetidas llevan la misma cantidad y el mismo importe a
 * propósito.</b> {@code findByIdWithDetails} carga {@code lineasDetalle} como
 * bolsa LAZY sin {@code ORDER BY} en ningún punto de la cadena, así que el orden
 * en que la BD devuelve las líneas no está garantizado (las dos facturas de este
 * escenario, con filas idénticas salvo el token, lo confirmaron: una devolvió
 * 5,6 y la otra 6,5). Con datos distintos por línea, "el artículo creado" y "el
 * artículo actualizado" dependerían de ese orden y la comparación entre las dos
 * corridas mediría el orden de la BD, no la ruta de lectura. Con la línea repetida
 * clonada, el estado final es el mismo en cualquier orden y las aserciones siguen
 * fijando lo que importa: una sola fila de artículo, dos precios y dos
 * movimientos.</p>
 *
 * <p><b>Fixtures:</b> todo lleva un token único por corrida (6 caracteres) en el
 * nombre del emisor, los nombres de artículo y los códigos de barras, así que las
 * dos facturas del escenario 1 no se ven entre sí ni colisionan con las filas de
 * otros escenarios en la base %test compartida; todo se borra en el bloque finally
 * (transacciones explícitas, como en
 * {@code ComprobantesRecibidosPrevalidationCabysTest}). Los dos artículos
 * preexistentes se siembran con status/processed true y un precio 777 para que la
 * rama de actualización sea distinguible de la de creación.</p>
 *
 * <p><b>Topología transaccional.</b> Las dos corridas se ejecutan con la misma:
 * el bucle de producción corre dentro del {@code @Transactional} del recurso
 * (PUT /procesar) y el oráculo dentro de un
 * {@link QuarkusTransaction#requiringNew()} explícito, con el usuario resuelto
 * DENTRO de la transacción como hace {@code currentUser()}. Así la única
 * diferencia entre ambas es la ruta de lectura, que es lo que se quiere comparar.</p>
 */
@QuarkusTest
@Tag("facturas-recibidas")
class ProcesarArticulosPrecargaTest extends support.ContextPathIsolation {

    private static final Logger LOG = Logger.getLogger(ProcesarArticulosPrecargaTest.class);

    private static final String BASE = "/Mercurius";
    private static final String API = BASE + "/api/app/facturas-recibidas";
    /** Usuario sembrado en la base %test: el mismo que resuelve currentUser(). */
    private static final String USUARIO = "admin";
    /** CAByS de 13 dígitos que el bucle copia a recomendacionCabys del artículo. */
    private static final String CABYS = "2349002011400";
    private static final String TIPO_INGRESO = "Ingreso Automatico por factura";

    @Inject ComprobantesRecibidosService recibidosService;
    @Inject ArticulosService articulosService;
    @Inject DepartamentoService departamentoService;
    @Inject InventarioService inventarioService;
    @Inject LineaDetalleService lineaDetalleService;
    @Inject LoginService loginService;
    @Inject Parser parser;
    @Inject EntityManager em;

    private static final AtomicInteger SECUENCIA = new AtomicInteger(1);

    // ── 1. Estado final idéntico al del bucle por línea ─────────────────

    @Test
    void facturaConNombresYCodigosRepetidosQuedaIgualQueElBuclePorLinea() {
        String tokenNuevo = token();
        String tokenLegacy = token();
        assertThat(tokenNuevo).as("las dos corridas van en universos separados").isNotEqualTo(tokenLegacy);
        ComprobantesRecibidos nueva = null;
        ComprobantesRecibidos legacy = null;
        try {
            // Mismos datos, dos universos aislados (nombres/códigos con token
            // distinto) para que las dos corridas no se contaminen.
            nueva = sembrarEscenario(tokenNuevo);
            legacy = sembrarEscenario(tokenLegacy);

            // Corrida 1: el recurso (bucle con precarga masiva).
            authed(adminSession())
                    .contentType(ContentType.URLENC)
                    .formParam("bucket", "todas")
                    .when().put(API + "/" + nueva.getId() + "/procesar")
                    .then().statusCode(200)
                    .body("data.message", equalTo("Se procesaron los artículos de la factura"));

            // Corrida 2: el oráculo, el bucle anterior línea por línea.
            procesarComoAntes(legacy.getId());

            // La caracterización: mismo estado final en ambos caminos.
            assertThat(snapshot(tokenNuevo))
                    .as("el bucle precargado debe dejar el mismo estado que el bucle por línea")
                    .isEqualTo(snapshot(tokenLegacy));

            // Y el estado exacto que se lee leyendo el código, no sólo la
            // igualdad entre las dos corridas.
            assertThat(snapshot(tokenNuevo)).containsExactlyInAnyOrder(
                    // Existentes por código de barras (líneas 1 y 7): se actualizan
                    // conservando su código de barras y su nombre sembrado.
                    "articulo PREC-NS-EX1 | barra=BAR-NS-2 | status=true | processed=false"
                            + " | cabys=" + CABYS + " | depto=EMISOR-NS | precios=[777.000, 10.000]",
                    "articulo PREC-NS-EX3 | barra=BAR-NS-5 | status=true | processed=false"
                            + " | cabys=" + CABYS + " | depto=EMISOR-NS | precios=[777.000, 10.000]",
                    // Existente por nombre (línea 4): se actualiza y conserva su
                    // código de barras nulo.
                    "articulo PREC-NS-EX2 | barra=null | status=true | processed=false"
                            + " | cabys=" + CABYS + " | depto=EMISOR-NS | precios=[777.000, 5.000]",
                    // Nombre nuevo (línea 2) y su repetida (línea 3): una sola fila
                    // con dos precios, no dos artículos.
                    "articulo NUEVO-NS-A | barra= | status=true | processed=false"
                            + " | cabys=" + CABYS + " | depto=EMISOR-NS | precios=[10.000, 10.000]",
                    // Código nuevo (línea 5) y su repetida (línea 6): igual, una sola
                    // fila con dos precios.
                    "articulo NUEVO-NS-B | barra=BAR-NS-3 | status=true | processed=false"
                            + " | cabys=" + CABYS + " | depto=EMISOR-NS | precios=[10.000, 10.000]",
                    "movimiento PREC-NS-EX1 | tipo=" + TIPO_INGRESO + " | cantidad=2.000 | unidades=2.000"
                            + " | status=true | processed=false | notas=",
                    "movimiento PREC-NS-EX3 | tipo=" + TIPO_INGRESO + " | cantidad=3.000 | unidades=3.000"
                            + " | status=true | processed=false | notas=",
                    "movimiento PREC-NS-EX2 | tipo=" + TIPO_INGRESO + " | cantidad=1.000 | unidades=1.000"
                            + " | status=true | processed=false | notas=",
                    "movimiento NUEVO-NS-A | tipo=" + TIPO_INGRESO + " | cantidad=6.000 | unidades=6.000"
                            + " | status=true | processed=false | notas=",
                    "movimiento NUEVO-NS-A | tipo=" + TIPO_INGRESO + " | cantidad=6.000 | unidades=6.000"
                            + " | status=true | processed=false | notas=",
                    "movimiento NUEVO-NS-B | tipo=" + TIPO_INGRESO + " | cantidad=6.000 | unidades=6.000"
                            + " | status=true | processed=false | notas=",
                    "movimiento NUEVO-NS-B | tipo=" + TIPO_INGRESO + " | cantidad=6.000 | unidades=6.000"
                            + " | status=true | processed=false | notas=",
                    // Un solo departamento para las siete líneas (el emisor), y las
                    // cinco filas de artículo apuntando a él.
                    "departamento EMISOR-NS | status=true | articulos=5",
                    "factura processed=true");

            // La línea 7 trae un nombre que no existe y un código que sí: manda el
            // código, así que se actualizó PREC-…-EX3 y el nombre no llegó a crearse.
            assertThat(articulosService.findByName("NUEVO-" + tokenNuevo + "-E"))
                    .as("una línea con código de barras conocido no debe crear su nombre").isNull();
            assertThat(articulosService.findByName("PREC-" + tokenNuevo + "-EX3"))
                    .as("el artículo del código de barras debe seguir siendo el suyo").isNotNull();
            // Y las facturas quedan marcadas, como antes.
            assertThat(recibidosService.findByIdWithDetails(nueva.getId()).getProcessed()).isTrue();
            assertThat(recibidosService.findByIdWithDetails(legacy.getId()).getProcessed()).isTrue();
        } finally {
            limpiarEscenario(tokenNuevo, nueva);
            limpiarEscenario(tokenLegacy, legacy);
        }
    }

    // ── 2. Comercial tipo "03" sin código: el NPE legacy se conserva ─────

    @Test
    void comercialTipo03SinCodigoFallaIgualQueAntesYNoProcesa() {
        String token = token();
        ComprobantesRecibidos factura = null;
        try {
            factura = sembrarFactura(token, List.of(
                    new Linea(1, "NULO-" + token, new BigDecimal("2"), new BigDecimal("20"),
                            List.of(new Comercial("03", null)))));

            authed(adminSession())
                    .contentType(ContentType.URLENC)
                    .when().put(API + "/" + factura.getId() + "/procesar")
                    .then().statusCode(500)
                    .body("error.code", equalTo("INTERNAL_ERROR"));

            // El oráculo falla en el mismo punto (codigoBarra.isEmpty() con null).
            Long idFactura = factura.getId();
            assertThrows(NullPointerException.class,
                    () -> QuarkusTransaction.requiringNew().run(
                            () -> procesarArticulosLegacy(
                                    recibidosService.findByIdWithDetails(idFactura), usuarioActual())));

            // Y no queda nada a medias: la línea moría antes de la primera
            // escritura, así que no hay artículo, ni movimiento, ni departamento.
            assertThat(snapshot(token)).containsExactly("factura processed=false");
        } finally {
            limpiarEscenario(token, factura);
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // Réplica del bucle ANTERIOR al cambio (oráculo de caracterización)
    // ══════════════════════════════════════════════════════════════════

    /**
     * Corrida completa como la hacía el recurso antes de la precarga: el bucle
     * literal de una lectura por línea y, detrás, el {@code processed = true} de
     * {@code doProcesar}. Va en una transacción explícita para igualar la
     * topología del {@code @Transactional} del recurso (PUT /procesar).
     */
    private void procesarComoAntes(long idFactura) {
        QuarkusTransaction.requiringNew().run(() -> {
            Usuarios usuario = usuarioActual();
            ComprobantesRecibidos factura = recibidosService.findByIdWithDetails(idFactura);
            procesarArticulosLegacy(factura, usuario);
            factura.setProcessed(true);
            recibidosService.update(factura);
        });
    }

    /** Usuario igual que {@code FacturasRecibidasResource.currentUser()}. */
    private Usuarios usuarioActual() {
        return loginService.findByUsername(USUARIO);
    }

    /**
     * Réplica LITERAL de {@code FacturasRecibidasResource.procesarArticulos}
     * ANTES de la carga masiva: {@code findByName} / {@code findByBarCode} y el
     * {@code createIfNotExist} por línea. Es el oráculo de caracterización: si la
     * carga por {@code WHERE IN} devolviera una fila distinta (o ninguna) para un
     * nombre o un código de barras, las huellas de estado final dejarían de
     * coincidir.
     *
     * <p>Única desviación respecto del original: el {@code LOG.warn} del
     * departamento, porque aquí el logger es el del test. No tocar más: es una
     * copia del código viejo a propósito.</p>
     */
    private void procesarArticulosLegacy(ComprobantesRecibidos factura, Usuarios usuario) {
        List<LineaDetalle> lineasDetalle = factura.getDetalles() == null
                ? null : factura.getDetalles().getLineasDetalle();
        if (lineasDetalle == null || lineasDetalle.isEmpty()) {
            if (factura.getDetalles() != null) {
                lineasDetalle = lineaDetalleService.listAllWhereID(factura.getDetalles().getId());
            }
            if (lineasDetalle == null || lineasDetalle.isEmpty()) {
                return; // paridad legacy: factura vacía → aborta sin cambios
            }
        }
        for (LineaDetalle linea : lineasDetalle) {
            String codigoBarra = "";
            String nombre = linea.getDetalle();
            List<CodigoComercial> comerciales = linea.getCodigosComerciales();
            if (comerciales != null) {
                for (CodigoComercial cc : comerciales) {
                    if (cc.getTipo() != null && cc.getTipo().contains("03")) {
                        codigoBarra = cc.getCodigo();
                    }
                }
            }

            Articulos articuloExistente = codigoBarra.isEmpty()
                    ? articulosService.findByName(nombre)
                    : articulosService.findByBarCode(codigoBarra);

            BigDecimal cantidad = linea.getCantidad();
            BigDecimal montoTotalLinea = linea.getMontoTotalLinea();
            BigDecimal totalUnitario = montoTotalLinea.divide(cantidad, 20, RoundingMode.HALF_UP);
            BigDecimal unidadesParseadas = parser.parseUnidadMedida(
                    linea.getUnidadMedida(), linea.getUnidadMedidaComercial()).multiply(cantidad);

            String nombreEmisor = (factura.getEncabezado() != null
                    && factura.getEncabezado().getEmisor() != null
                    && factura.getEncabezado().getEmisor().getNombre() != null)
                    ? factura.getEncabezado().getEmisor().getNombre() : "Sin emisor";
            Departamento departamento = new Departamento();
            departamento.setNombre(nombreEmisor);
            departamento.setStatus(true);
            departamento.setUsuario(usuario);
            Departamento persistido = departamentoService.createIfNotExist(departamento);
            if (persistido == null) {
                LOG.warn("No se pudo crear u obtener el departamento '" + nombreEmisor
                        + "' | fuente=ProcesarArticulosPrecargaTest.procesarArticulosLegacy()");
            }

            Articulos articuloFinal;
            if (articuloExistente == null) {
                Articulos articulo = new Articulos();
                articulo.setNombre(nombre);
                articulo.setCodigoBarra(codigoBarra);
                articulo.setRecomendacionCabys(linea.getCodigoCabys());
                articulo.setDepartamento(persistido);
                articulo.setUnidadMedida(linea.getUnidadMedida());
                articulo.setUnidadMedidaComercial(linea.getUnidadMedidaComercial());
                articulo.setUsuario(usuario);
                articulo.setStatus(true);
                articulo.setProcessed(false);
                ArticuloPrecio precio = new ArticuloPrecio();
                precio.setArticulo(articulo);
                precio.setPrecioCostoSinIVA(totalUnitario);
                List<ArticuloPrecio> precios = new ArrayList<>();
                precios.add(precio);
                articulo.setPrecios(precios);
                articulosService.create(articulo);
                articuloFinal = articulo;
            } else {
                articuloExistente.setRecomendacionCabys(linea.getCodigoCabys());
                articuloExistente.setUnidadMedida(linea.getUnidadMedida());
                articuloExistente.setUnidadMedidaComercial(linea.getUnidadMedidaComercial());
                articuloExistente.setDepartamento(persistido);
                articuloExistente.setUsuario(usuario);
                articuloExistente.setStatus(true);
                articuloExistente.setProcessed(false);
                ArticuloPrecio precio = new ArticuloPrecio();
                precio.setArticulo(articuloExistente);
                precio.setPrecioCostoSinIVA(totalUnitario);
                precio.setPrecioConUtilidad(BigDecimal.ZERO);
                precio.setPrecioFinal(BigDecimal.ZERO);
                List<ArticuloPrecio> precios = articuloExistente.getPrecios();
                if (precios == null) {
                    precios = new ArrayList<>();
                }
                precios.add(precio);
                articuloExistente.setPrecios(precios);
                articulosService.update(articuloExistente);
                articuloFinal = articuloExistente;
            }

            boolean notaCredito = "02".equals(factura.getEncabezado() != null
                    ? factura.getEncabezado().getCodigoDocumento() : null);
            Inventario movimiento = new Inventario();
            movimiento.setArticulo(articuloFinal);
            movimiento.setUnidadesRecomendadasFactura(unidadesParseadas);
            movimiento.setUsuario(usuario);
            movimiento.setFechaMovimiento(new java.util.Date());
            movimiento.setTipoMovimiento(notaCredito
                    ? "Egreso Automatico por nota de credito" : TIPO_INGRESO);
            movimiento.setStatus(true);
            movimiento.setProcessed(false);
            movimiento.setCantidad(notaCredito ? cantidad.negate() : cantidad);
            movimiento.setNotas(notaCredito ? "Nota de credito - egreso de inventario" : "");
            inventarioService.create(movimiento);
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // Huella del estado final (lo que las dos corridas deben compartir)
    // ══════════════════════════════════════════════════════════════════

    /**
     * Estado final de una corrida, con el token de la corrida normalizado a
     * "NS" para que las dos facturas (que no pueden compartir nombres) generen
     * huellas comparables. Sólo escalares: las consultas proyectan el
     * departamento y los precios con JOIN, así que no se tocan proxies fuera de
     * transacción.
     */
    private List<String> snapshot(String token) {
        String patron = "%" + token + "%";
        List<String> filas = new ArrayList<>();

        for (Object[] fila : em.createQuery(
                "SELECT a.nombre, a.codigoBarra, a.status, a.processed, a.recomendacionCabys, d.nombre "
                        + "FROM Articulos a LEFT JOIN a.departamento d "
                        + "WHERE a.nombre LIKE :pat ORDER BY a.nombre, a.codigo", Object[].class)
                .setParameter("pat", patron).getResultList()) {
            filas.add("articulo " + norm((String) fila[0], token)
                    + " | barra=" + norm((String) fila[1], token)
                    + " | status=" + fila[2]
                    + " | processed=" + fila[3]
                    + " | cabys=" + norm((String) fila[4], token)
                    + " | depto=" + norm((String) fila[5], token)
                    + " | precios=" + preciosDe((String) fila[0]));
        }

        for (Object[] fila : em.createQuery(
                "SELECT ar.nombre, i.tipoMovimiento, i.cantidad, i.unidadesRecomendadasFactura, "
                        + "i.status, i.processed, i.notas "
                        + "FROM Inventario i JOIN i.articulo ar "
                        + "WHERE ar.nombre LIKE :pat ORDER BY ar.nombre, i.codigo", Object[].class)
                .setParameter("pat", patron).getResultList()) {
            filas.add("movimiento " + norm((String) fila[0], token)
                    + " | tipo=" + fila[1]
                    + " | cantidad=" + escala((BigDecimal) fila[2])
                    + " | unidades=" + escala((BigDecimal) fila[3])
                    + " | status=" + fila[4]
                    + " | processed=" + fila[5]
                    + " | notas=" + norm((String) fila[6], token));
        }

        // El departamento del emisor y cuántos artículos quedan colgados de él:
        // fija que las seis líneas comparten fila y que las cuatro de artículo
        // apuntan a la misma.
        for (Object[] fila : em.createQuery(
                "SELECT d.nombre, d.status, (SELECT COUNT(a) FROM Articulos a "
                        + "WHERE a.departamento.nombre = d.nombre) "
                        + "FROM Departamento d WHERE d.nombre = :nombre ORDER BY d.id", Object[].class)
                .setParameter("nombre", "EMISOR-" + token).getResultList()) {
            filas.add("departamento " + norm((String) fila[0], token)
                    + " | status=" + fila[1] + " | articulos=" + fila[2]);
        }

        filas.add("factura processed=" + facturaProcesada(token));
        Collections.sort(filas);
        return filas;
    }

    /** Precios de un artículo, en orden de inserción y con escala fija. */
    private String preciosDe(String nombre) {
        return precios(nombre).stream().map(ProcesarArticulosPrecargaTest::escala)
                .collect(Collectors.joining(", ", "[", "]"));
    }

    private List<BigDecimal> precios(String nombre) {
        return em.createQuery(
                "SELECT p.precioCostoSinIVA FROM ArticuloPrecio p "
                        + "WHERE p.articulo.nombre = :nombre ORDER BY p.id", BigDecimal.class)
                .setParameter("nombre", nombre).getResultList();
    }

    /** La factura del token (una por corrida, con consecutivo único). */
    private Boolean facturaProcesada(String token) {
        return em.createQuery(
                "SELECT f.processed FROM ComprobantesRecibidos f "
                        + "JOIN f.encabezado e WHERE e.numeroConsecutivo LIKE :pat", Boolean.class)
                .setParameter("pat", "%" + token + "%").getSingleResult();
    }

    private static String norm(String valor, String token) {
        return valor == null ? "null" : valor.replace(token, "NS");
    }

    /** Escala fija para que la comparación no dependa de la columna. */
    private static String escala(BigDecimal valor) {
        return valor == null ? "null" : valor.setScale(3, RoundingMode.HALF_UP).toPlainString();
    }

    // ══════════════════════════════════════════════════════════════════
    // Fixtures
    // ══════════════════════════════════════════════════════════════════

    private record Comercial(String tipo, String codigo) {}

    private record Linea(int numero, String detalle, BigDecimal cantidad, BigDecimal monto,
                         List<Comercial> comerciales) {}

    /** Token único por corrida: aísla las facturas de la base %test compartida. */
    private static String token() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase(Locale.ROOT);
    }

    /**
     * Factura del escenario 1: las siete líneas que fijan el criterio de
     * resolución, más los tres artículos preexistentes que deben actualizarse.
     * Las líneas repetidas van clonadas (misma cantidad e importe) para que el
     * estado final no dependa del orden en que la BD devuelva las líneas.
     */
    private ComprobantesRecibidos sembrarEscenario(String token) {
        sembrarArticulo("PREC-" + token + "-EX1", "BAR-" + token + "-2");
        sembrarArticulo("PREC-" + token + "-EX2", null);
        sembrarArticulo("PREC-" + token + "-EX3", "BAR-" + token + "-5");
        return sembrarFactura(token, List.of(
                // Existente por código de barras: el comercial "01" se ignora y
                // manda el "03" (no se pone un segundo "03" porque el bucle se
                // queda con el último y el orden de codigosComerciales —otra
                // bolsa sin ORDER BY— no está garantizado).
                new Linea(1, "PREC-" + token + "-EX1", new BigDecimal("2"), new BigDecimal("20"),
                        List.of(new Comercial("01", "NOBAR-" + token),
                                new Comercial("03", "BAR-" + token + "-2"))),
                // Nombre nuevo sin código de barras: se crea.
                new Linea(2, "NUEVO-" + token + "-A", new BigDecimal("6"), new BigDecimal("60"), List.of()),
                // El mismo nombre repetido: actualiza el que se acaba de crear.
                new Linea(3, "NUEVO-" + token + "-A", new BigDecimal("6"), new BigDecimal("60"), List.of()),
                // Existente por nombre, sin código de barras: se actualiza.
                new Linea(4, "PREC-" + token + "-EX2", new BigDecimal("1"), new BigDecimal("5"), List.of()),
                // Código nuevo: se crea con ese código.
                new Linea(5, "NUEVO-" + token + "-B", new BigDecimal("6"), new BigDecimal("60"),
                        List.of(new Comercial("03", "BAR-" + token + "-3"))),
                // El mismo código repetido: actualiza, no duplica.
                new Linea(6, "NUEVO-" + token + "-B", new BigDecimal("6"), new BigDecimal("60"),
                        List.of(new Comercial("03", "BAR-" + token + "-3"))),
                // Nombre que no existe + código que sí (el de PREC-…-EX3): manda
                // el código, así que "NUEVO-…-E" no llega a existir.
                new Linea(7, "NUEVO-" + token + "-E", new BigDecimal("3"), new BigDecimal("30"),
                        List.of(new Comercial("01", "NOBAR2-" + token),
                                new Comercial("03", "BAR-" + token + "-5")))));
    }

    /** Artículo preexistente: status/processed true y un precio 777 reconocible. */
    private Articulos sembrarArticulo(String nombre, String codigoBarra) {
        Articulos articulo = new Articulos();
        articulo.setNombre(nombre);
        articulo.setCodigoBarra(codigoBarra);
        articulo.setStatus(true);
        articulo.setProcessed(true);
        ArticuloPrecio precio = new ArticuloPrecio();
        precio.setArticulo(articulo);
        precio.setPrecioCostoSinIVA(new BigDecimal("777.00"));
        List<ArticuloPrecio> precios = new ArrayList<>();
        precios.add(precio);
        articulo.setPrecios(precios);
        articulosService.create(articulo);
        assertThat(articulo.getCodigo())
                .as("el artículo preexistente " + nombre + " debe sembrarse").isNotNull();
        return articulo;
    }

    /**
     * Factura activa con su detalle, sus líneas y sus códigos comerciales. El
     * consecutivo lleva el token para que {@link #facturaProcesada(String)} la
     * encuentre, y se esquiva el 8888 que el recurso lee como documento
     * manipulado.
     */
    private ComprobantesRecibidos sembrarFactura(String token, List<Linea> lineas) {
        Emisor emisor = new Emisor();
        emisor.setNombre("EMISOR-" + token);

        Encabezado encabezado = new Encabezado();
        encabezado.setNumeroConsecutivo(consecutivoUnico(token));
        encabezado.setFechaEmision(LocalDateTime.now().minusDays(1));
        encabezado.setCondicionVenta("01");
        encabezado.setCodigoDocumento("01");
        encabezado.setSchemaVersion("4.4");
        encabezado.setEmisor(emisor);

        DetalleServicio detalles = new DetalleServicio();
        List<LineaDetalle> lineasDetalle = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (Linea spec : lineas) {
            LineaDetalle linea = new LineaDetalle();
            linea.setNumeroLinea(spec.numero());
            linea.setDetalle(spec.detalle());
            linea.setCodigoCabys(CABYS);
            linea.setCantidad(spec.cantidad());
            linea.setMontoTotalLinea(spec.monto());
            linea.setMontoTotal(spec.monto());
            linea.setUnidadMedida("Unid");
            linea.setUnidadMedidaComercial("UND");
            linea.setDetalleServicio(detalles);
            List<CodigoComercial> comerciales = new ArrayList<>();
            for (Comercial comercial : spec.comerciales()) {
                CodigoComercial codigo = new CodigoComercial();
                codigo.setTipo(comercial.tipo());
                codigo.setCodigo(comercial.codigo());
                codigo.setLineaDetalle(linea);
                comerciales.add(codigo);
            }
            linea.setCodigosComerciales(comerciales);
            lineasDetalle.add(linea);
            total = total.add(spec.monto());
        }
        detalles.setLineasDetalle(lineasDetalle);

        ResumenFactura resumen = new ResumenFactura();
        resumen.setTotalComprobante(total);
        resumen.setTotalVentaNeta(total);
        resumen.setTotalImpuesto(BigDecimal.ZERO);

        ComprobantesRecibidos comprobante = new ComprobantesRecibidos();
        comprobante.setEncabezado(encabezado);
        comprobante.setDetalles(detalles);
        comprobante.setResumen(resumen);
        comprobante.setStatus(true);
        comprobante.setProcessed(false);
        comprobante.setPaid(false);
        comprobante.setSchemaVersion("4.4");
        comprobante.setUser(USUARIO);
        recibidosService.create(comprobante);
        assertThat(comprobante.getId()).as("la factura debe sembrarse").isNotNull();
        return comprobante;
    }

    /** 20 dígitos con el token dentro, sin la secuencia que el recurso marca. */
    private static String consecutivoUnico(String token) {
        String consecutivo;
        do {
            consecutivo = "506" + token
                    + String.format(Locale.ROOT, "%011d", SECUENCIA.getAndIncrement());
        } while (consecutivo.contains("8888"));
        return consecutivo;
    }

    // ══════════════════════════════════════════════════════════════════
    // Limpieza (la base %test la comparten todas las suites)
    // ══════════════════════════════════════════════════════════════════

    /**
     * Retira todo lo de la corrida. Va en transacciones explícitas y con
     * {@code em.remove} en cascada (hijos antes que padres) porque la base es
     * compartida; si algo ya no está, el fallo se registra pero no tapa el
     * resultado del escenario.
     */
    private void limpiarEscenario(String token, ComprobantesRecibidos factura) {
        try {
            String patron = "%" + token + "%";
            Long idFactura = factura == null ? null : factura.getId();
            Long idDetalle = factura == null || factura.getDetalles() == null
                    ? null : factura.getDetalles().getId();
            Long idEmisor = factura == null || factura.getEncabezado() == null
                    || factura.getEncabezado().getEmisor() == null
                    ? null : factura.getEncabezado().getEmisor().getId();
            QuarkusTransaction.requiringNew().run(() -> {
                for (Inventario movimiento : em.createQuery(
                        "SELECT i FROM Inventario i JOIN i.articulo a "
                                + "WHERE a.nombre LIKE :pat", Inventario.class)
                        .setParameter("pat", patron).getResultList()) {
                    em.remove(movimiento);
                }
                for (Articulos articulo : em.createQuery(
                        "SELECT a FROM Articulos a WHERE a.nombre LIKE :pat", Articulos.class)
                        .setParameter("pat", patron).getResultList()) {
                    em.remove(articulo);
                }
                for (Departamento departamento : em.createQuery(
                        "SELECT d FROM Departamento d WHERE d.nombre = :nombre", Departamento.class)
                        .setParameter("nombre", "EMISOR-" + token).getResultList()) {
                    em.remove(departamento);
                }
                if (idFactura != null) {
                    ComprobantesRecibidos fila = em.find(ComprobantesRecibidos.class, idFactura);
                    if (fila != null) {
                        em.remove(fila);
                    }
                }
                if (idDetalle != null) {
                    for (LineaDetalle linea : em.createQuery(
                            "SELECT l FROM LineaDetalle l WHERE l.detalleServicio.id = :id", LineaDetalle.class)
                            .setParameter("id", idDetalle).getResultList()) {
                        em.remove(linea);
                    }
                    DetalleServicio detalle = em.find(DetalleServicio.class, idDetalle);
                    if (detalle != null) {
                        em.remove(detalle);
                    }
                }
            });
            // El emisor se va en su propia transacción: el encabezado que lo
            // referencia desaparece en la anterior.
            if (idEmisor != null) {
                Long id = idEmisor;
                QuarkusTransaction.requiringNew().run(() -> {
                    Emisor emisor = em.find(Emisor.class, id);
                    if (emisor != null) {
                        em.remove(emisor);
                    }
                });
            }
        } catch (RuntimeException e) {
            // La limpieza es best-effort sobre una base compartida: un fallo aquí
            // no debe tapar la aserción del escenario, pero tampoco puede ser
            // silencioso.
            LOG.warn("No se pudo limpiar del todo la corrida " + token, e);
        }
    }

    // ── Auth (recipe de las suites T36) ─────────────────────────────────

    private static Map<String, String> adminSession() {
        Response loginPage = given().redirects().follow(false)
                .when().get(BASE + "/login");
        loginPage.then().statusCode(200);
        Map<String, String> cookies = new HashMap<>(loginPage.getCookies());

        Response login = given().redirects().follow(false)
                .cookies(cookies)
                .contentType(ContentType.URLENC)
                .formParam("j_username", USUARIO)
                .formParam("j_password", "admin123")
                .when().post(BASE + "/j_security_check");
        login.then().statusCode(302);
        cookies.putAll(login.getCookies());
        return cookies;
    }

    private static RequestSpecification authed(Map<String, String> cookies) {
        RequestSpecification spec = given().redirects().follow(false).cookies(cookies);
        String token = cookies.get("csrftoken");
        if (token == null) {
            token = cookies.get("csrf-token");
        }
        if (token != null) {
            spec.header("X-CSRF-TOKEN", token);
        }
        return spec;
    }
}
