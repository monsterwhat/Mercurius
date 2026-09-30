package Services;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import Models.Cabys;
import Models.ComprobantesRecibidos;
import Models.Detalles.DetalleServicio;
import Models.Detalles.LineaDetalle;
import Models.Encabezado.Encabezado;
import Models.Validacion.PrevalidationResult;
import Models.Validacion.ValidationError;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Equivalencia de resultados de
 * {@link ComprobantesRecibidosPrevalidationService#validarCabys(List, PrevalidationResult)}
 * tras sustituir el {@code cabysService.find(codigo)} POR LÍNEA por una única
 * carga masiva del catálogo ({@code WHERE c.codigo IN :codigos}).
 *
 * <p><b>Qué prueba y qué no.</b> El cambio es una optimización de I/O, así que
 * lo que debe quedar intacto es la decisión de aceptación/rechazo. No hay
 * infraestructura de conteo de consultas en la suite, por lo que NO se afirma
 * sobre el número de consultas: se afirma sobre el
 * {@link PrevalidationResult} (categoría, código, severidad y mensaje de cada
 * incidencia CAByS) y, en paralelo, contra una réplica del bucle ANTERIOR al
 * cambio ({@link #validarCabysComoAntes(List)}, un {@code find()} por línea)
 * que actúa como oráculo de caracterización. La reducción N→1 se documenta por
 * inspección del código, no aquí.</p>
 *
 * <p><b>Escenarios:</b> (1) mezcla de líneas —válida, código repetido,
 * INACTIVO, código con espacios alrededor, ausente del catálogo, mal formado,
 * nulo y en blanco— comparada incidencia por incidencia contra el bucle anterior
 * y contra la lista esperada en el orden exacto que emite el bucle;
 * (2) factura sólo con códigos válidos: cero incidencias CAByS y factura
 * aceptada, con independencia del perfil estricto/indulgente activo;
 * (3) INACTIVO repetido: un aviso por línea, no por código; (4) factura con
 * líneas no consultables: ni una consulta al catálogo, tal como antes.</p>
 *
 * <p><b>Fixtures:</b> los códigos CAByS se generan por corrida (13 dígitos,
 * patrón {@code \d{13}} que exige el validador) y se siembran ACTIVO/INACTIVO
 * con {@code CabysService.create()}, igual que
 * {@code CabysImportValidationTest}; se borran en el bloque finally porque la
 * base %test es compartida. La factura NO se persiste: se construye en memoria
 * y se valida por la sobrecarga {@code prevalidarCompleto(ComprobantesRecibidos)},
 * la misma que usa {@code ComprobantesRecibidosService.createWithRelatedEntities}.
 * Se evita deliberadamente el código {@code 0111010010010}, que
 * {@code CabysService.find()} persiste al vuelo cuando no lo encuentra.</p>
 */
@QuarkusTest
@Tag("prevalidacion")
class ComprobantesRecibidosPrevalidationCabysTest {

    /** Código con caso especial en {@code CabysService.find()}: se persiste al vuelo. */
    private static final String CABYS_CON_RESPALDO = "0111010010010";

    @Inject ComprobantesRecibidosPrevalidationService prevalidationService;

    @Inject CabysService cabysService;

    @Inject PrevalidationConfigService prevalidationConfigService;

    @Inject EntityManager em;

    // ── Fixtures ──────────────────────────────────────────────────────────────

    /** 13 dígitos, único por corrida, nunca el del respaldo de {@code find()}. */
    private static String codigoUnico() {
        String codigo;
        do {
            codigo = String.format(Locale.ROOT, "%013d",
                    Math.abs(UUID.randomUUID().getLeastSignificantBits() % 1_000_000_000_0000L));
        } while (CABYS_CON_RESPALDO.equals(codigo));
        return codigo;
    }

    private void sembrarCabys(String codigo, String estado) {
        if (cabysService.find(codigo) == null) {
            cabysService.create(new Cabys(codigo,
                    "T36 - CAByS de prueba " + codigo,
                    "Alimentos y bebidas", "13",
                    "https://www.hacienda.go.cr/cabys/" + codigo, estado));
        }
    }

    /**
     * Borra los CAByS sembrados por el escenario.
     *
     * <p>Se envuelve en una transacción explícita porque
     * {@code CabysService.delete()} pisa el {@code @Transactional} de
     * {@code GService.delete()} sin volver a anotarlo, así que {@code em.remove()}
     * corre sin transacción y falla con "Transaction is not active" (el mismo
     * borrado que hace {@code CabysImportValidationTest} deja la fila en la base
     * %test). La factura no se persiste, así que los únicos rows que hay que
     * retirar son los del catálogo.
     */
    private void borrarCabys(String... codigos) {
        QuarkusTransaction.requiringNew().run(() -> {
            for (String codigo : codigos) {
                Cabys cabys = em.find(Cabys.class, codigo);
                if (cabys != null) {
                    em.remove(cabys);
                }
            }
        });
    }

    /** Línea con {@code numeroLinea} propio, para etiqueta determinista. */
    private static LineaDetalle linea(int numero, String codigoCabys) {
        LineaDetalle linea = new LineaDetalle();
        linea.setNumeroLinea(numero);
        linea.setCodigoCabys(codigoCabys);
        // detalle único por línea: la etiqueta de las líneas SIN numeroLinea se
        // resuelve con lineas.indexOf(linea), que compara con el equals() de
        // Lombok, así que dos líneas idénticas colapsarían en la misma posición.
        linea.setDetalle("Linea " + numero + " [" + codigoCabys + "]");
        return linea;
    }

    /** Línea sin {@code numeroLinea}: la etiqueta sale del índice dentro de la lista. */
    private static LineaDetalle lineaSinNumero(int posicion, String codigoCabys) {
        LineaDetalle linea = linea(posicion, codigoCabys);
        linea.setNumeroLinea(null);
        return linea;
    }

    private static ComprobantesRecibidos facturaCon(LineaDetalle... lineas) {
        Encabezado encabezado = new Encabezado();
        encabezado.setNumeroConsecutivo("00000000000000000001");
        encabezado.setCodigoDocumento("01");
        // v4.3 a propósito: los avisos propios de v4.4 (Resumen.MedioPago y
        // CodigoActividadReceptor) no deben ensuciar un escenario que mide sólo
        // las incidencias CAByS. Sin receptor ni resumen tampoco hay ruido.
        encabezado.setSchemaVersion("4.3");

        DetalleServicio detalles = new DetalleServicio();
        detalles.setLineasDetalle(new ArrayList<>(List.of(lineas)));

        ComprobantesRecibidos comprobante = new ComprobantesRecibidos();
        comprobante.setEncabezado(encabezado);
        comprobante.setDetalles(detalles);
        return comprobante;
    }

    // ── Helpers de aserción ───────────────────────────────────────────────────

    /**
     * Huella de las incidencias CAByS del resultado, en el orden en que las
     * emite el bucle: código + severidad + mensaje. El mensaje es parte de la
     * huella a propósito, porque es lo que se persiste en
     * {@code ComprobantesRecibidos.prevalidationErrors} y lo que ve el usuario.
     */
    private static List<String> incidenciasCabys(PrevalidationResult result) {
        return result.getAllIssues().stream()
                .filter(incidencia -> incidencia.getCategory() == ValidationError.Category.CABYS)
                .map(incidencia -> incidencia.getCode() + "/" + incidencia.getSeverity() + "/" + incidencia.getMessage())
                .toList();
    }

    /**
     * Réplica del bucle ANTERIOR al cambio (un {@code find()} por línea), copiada
     * del cuerpo original de {@code validarCabys}. Es el oráculo de
     * caracterización: si el catálogo masivo devolviera una fila distinta (o
     * ninguna) para un código, las huellas dejarían de coincidir.
     */
    private PrevalidationResult validarCabysComoAntes(List<LineaDetalle> lineas) {
        PrevalidationResult result = new PrevalidationResult();

        for (LineaDetalle linea : lineas) {
            String codigo = linea.getCodigoCabys();
            if (codigo == null || codigo.trim().isEmpty()) {
                result.addError(new ValidationError(
                    ValidationError.Category.valueOf("CABYS"),
                    "codigoCabys", "EMPTY_CABYS",
                    "Línea " + (linea.getNumeroLinea() != null ? linea.getNumeroLinea() : String.valueOf(lineas.indexOf(linea) + 1)) +
                    ": código CAByS faltante"));
                continue;
            }

            codigo = codigo.trim();

            if (!codigo.matches("\\d{13}")) {
                result.addError(new ValidationError(
                    ValidationError.Category.valueOf("CABYS"),
                    "codigoCabys", "INVALID_FORMAT",
                    "El código CAByS '" + codigo + "' no tiene 13 dígitos",
                    "13 dígitos", codigo));
                continue;
            }

            Cabys cabys = cabysService.find(codigo);
            if (cabys == null) {
                String msg = "El código CAByS '" + codigo + "' no fue encontrado en el catálogo local";
                if (prevalidationConfigService.getActiveConfig().isCabysStrictMode()) {
                    result.addError(new ValidationError(
                        ValidationError.Category.valueOf("CABYS"),
                        "codigoCabys", "MISSING_CABYS",
                        msg));
                } else {
                    result.addWarning(new ValidationError(
                        ValidationError.Category.valueOf("CABYS"),
                        "codigoCabys", "MISSING_CABYS",
                        msg,
                        ValidationError.Severity.WARNING));
                }
                continue;
            }

            if (cabys.getEstado() != null && !cabys.getEstado().trim().isEmpty()
                    && !"ACTIVO".equalsIgnoreCase(cabys.getEstado())) {
                result.addWarning(new ValidationError(
                    ValidationError.Category.valueOf("CABYS"),
                    "codigoCabys", "INACTIVE_CABYS",
                    "El código CAByS '" + codigo + "' no está ACTIVO (estado=" + cabys.getEstado() + ")",
                    ValidationError.Severity.WARNING));
            }
        }
        return result;
    }

    // ── 1. Mezcla de líneas: mismas incidencias que el bucle anterior ─────────

    @Test
    void mezclaDeLineasProduceLasMismasIncidenciasQueElBucleAnterior() {
        String activo = codigoUnico();
        String inactivo = codigoUnico();
        String ausente = codigoUnico();
        try {
            sembrarCabys(activo, "ACTIVO");
            sembrarCabys(inactivo, "INACTIVO");
            assertThat(cabysService.find(ausente)).as("el fixture exige un código ausente").isNull();

            List<LineaDetalle> lineas = List.of(
                    linea(1, activo),                 // catálogo: sin incidencia
                    linea(2, activo),                 // repetido: el aviso es por línea, no por código
                    linea(3, inactivo),               // INACTIVO → aviso
                    linea(4, "  " + activo + "  "),   // espacios: el bucle recorta antes de consultar
                    linea(5, ausente),                // 13 dígitos que no están en el catálogo
                    linea(6, "999"),                  // mal formado: ni se consulta
                    lineaSinNumero(7, null),          // faltante, etiqueta por índice
                    linea(8, "   "));                 // faltante, en blanco

            PrevalidationResult actual = prevalidationService.prevalidarCompleto(
                    facturaCon(lineas.toArray(new LineaDetalle[0])));

            // Mismos mensajes, códigos y severidades que el bucle con un find() por línea.
            assertThat(incidenciasCabys(actual))
                    .isEqualTo(incidenciasCabys(validarCabysComoAntes(lineas)));

            // Y el conjunto exacto de incidencias, mensaje incluido, con la
            // severidad de MISSING_CABYS tomada del perfil activo (estricto →
            // error, indulgente → aviso) para que la aserción no dependa de la
            // configuración de la base %test. Sin importar el orden porque
            // getAllIssues() agrupa primero los errores y luego los avisos, así
            // que su orden no es el del bucle; el orden del bucle sí queda
            // cubierto por la comparación contra el oráculo de arriba.
            String severidadAusente = prevalidationConfigService.getActiveConfig().isCabysStrictMode()
                    ? "ERROR" : "WARNING";
            assertThat(incidenciasCabys(actual)).containsExactlyInAnyOrder(
                    "INACTIVE_CABYS/WARNING/El código CAByS '" + inactivo
                            + "' no está ACTIVO (estado=INACTIVO)",
                    "MISSING_CABYS/" + severidadAusente + "/El código CAByS '" + ausente
                            + "' no fue encontrado en el catálogo local",
                    "INVALID_FORMAT/ERROR/El código CAByS '999' no tiene 13 dígitos",
                    "EMPTY_CABYS/ERROR/Línea 7: código CAByS faltante",
                    "EMPTY_CABYS/ERROR/Línea 8: código CAByS faltante");

            // Decisión de rechazo. Se afirma sobre hasErrors(), que es lo que
            // consume la decisión real (FacturasRecibidasResource.toPanel publica
            // isValid = !hasErrors()): el campo isValid de PrevalidationResult
            // nace en false y sólo addError() lo vuelve a escribir, así que
            // afirmar sobre él no probaría nada. Y el código ACTIVO no aparece en
            // ningún mensaje: el catálogo masivo sirvió la fila correcta para las
            // cuatro líneas que lo consultan (1, 2, 3 y la recortada 4).
            assertThat(actual.hasErrors()).isTrue();
            assertThat(incidenciasCabys(actual)).noneMatch(incidencia -> incidencia.contains(activo));
        } finally {
            borrarCabys(activo, inactivo);
        }
    }

    // ── 2. Sólo códigos válidos: factura aceptada ────────────────────────────

    @Test
    void facturaConSoloCodigosValidosNoReportaErroresYQuedaAceptable() {
        String activo = codigoUnico();
        try {
            sembrarCabys(activo, "ACTIVO");

            List<LineaDetalle> lineas = List.of(
                    linea(1, activo),
                    linea(2, activo),
                    linea(3, "  " + activo + "  "));

            PrevalidationResult result = prevalidationService.prevalidarCompleto(
                    facturaCon(lineas.toArray(new LineaDetalle[0])));

            assertThat(incidenciasCabys(result)).isEmpty();
            assertThat(result.hasErrors()).isFalse();
        } finally {
            borrarCabys(activo);
        }
    }

    // ── 3. INACTIVO repetido: un aviso por línea, no por código ───────────────

    @Test
    void codigoInactivoRepetidoAvisaUnaVezPorLinea() {
        String inactivo = codigoUnico();
        try {
            sembrarCabys(inactivo, "INACTIVO");

            List<LineaDetalle> lineas = List.of(
                    linea(1, inactivo),
                    linea(2, inactivo),
                    linea(3, inactivo));

            PrevalidationResult result = prevalidationService.prevalidarCompleto(
                    facturaCon(lineas.toArray(new LineaDetalle[0])));

            assertThat(incidenciasCabys(result)).containsExactly(
                    "INACTIVE_CABYS/WARNING/El código CAByS '" + inactivo + "' no está ACTIVO (estado=INACTIVO)",
                    "INACTIVE_CABYS/WARNING/El código CAByS '" + inactivo + "' no está ACTIVO (estado=INACTIVO)",
                    "INACTIVE_CABYS/WARNING/El código CAByS '" + inactivo + "' no está ACTIVO (estado=INACTIVO)");
            // Un aviso nunca rechaza la factura.
            assertThat(result.hasErrors()).isFalse();
        } finally {
            borrarCabys(inactivo);
        }
    }

    // ── 4. Ninguna línea consultable: sin catálogo y mismas incidencias ───────

    @Test
    void lineasNoConsultablesNoReportanIncidenciasDeCatalogo() {
        // Sin siembras: ningún código llega a ser de 13 dígitos, así que el
        // validador no debe abrir la consulta masiva ni individual (y el
        // resultado es el mismo que producía el bucle anterior).
        List<LineaDetalle> lineas = List.of(
                linea(1, null),
                linea(2, "   "),
                linea(3, "12"),
                lineaSinNumero(4, "ABC123"));

        PrevalidationResult result = prevalidationService.prevalidarCompleto(
                facturaCon(lineas.toArray(new LineaDetalle[0])));

        assertThat(incidenciasCabys(result)).isEqualTo(incidenciasCabys(validarCabysComoAntes(lineas)));
        assertThat(incidenciasCabys(result)).containsExactly(
                "EMPTY_CABYS/ERROR/Línea 1: código CAByS faltante",
                "EMPTY_CABYS/ERROR/Línea 2: código CAByS faltante",
                "INVALID_FORMAT/ERROR/El código CAByS '12' no tiene 13 dígitos",
                "INVALID_FORMAT/ERROR/El código CAByS 'ABC123' no tiene 13 dígitos");
        assertThat(result.hasErrors()).isTrue();
    }
}
