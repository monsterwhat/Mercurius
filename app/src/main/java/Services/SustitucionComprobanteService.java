package Services;

import Models.ComprobantesEmitidos;
import Models.Enums.Tipo_CodigosReferencia;
import Models.Referencias.InformacionReferencia;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * Sustitución de comprobantes electrónicos: re-emisión por rechazo (Art. 19 del
 * Reglamento de Comprobantes Electrónicos) y emparejamiento de los códigos de la
 * nota 9 que obligan a partir del 2026-11-01.
 *
 * <p><b>Art. 19 — un rechazo NO se corrige con nota de crédito.</b> El documento
 * rechazado no tiene validez fiscal y el emisor debe emitir INMEDIATAMENTE un
 * comprobante NUEVO que lo referencie. El reglamento lo dice en términos:
 * "Para efectos tributarios no debe realizarse la respectiva nota de crédito"
 * (material del MH: los comprobantes rechazados "no requieren Notas de Crédito").
 * Por eso la única vía de corrección de un rechazo es la re-emisión con el
 * código 16 "Sustituye comprobante electrónico rechazado"; la nota de crédito
 * queda reservada para devoluciones reales sobre comprobantes aceptados.
 *
 * <p><b>Pares obligatorios de la nota 9</b> ({@code CodigoReferenciaType} de los
 * XSD oficiales v4.4):
 * <ul>
 *   <li>13 "Anula documento de referencia por error material" — si además se
 *       genera un documento de reemplazo, ese reemplazo DEBE llevar 15
 *       "Sustituye comprobante electrónico por error material" sobre el mismo
 *       documento de referencia.</li>
 *   <li>14 "Corrige monto por error material" — efecto contable en el MISMO
 *       periodo que el documento que se modifica.</li>
 *   <li>15 — nunca puede viajar solo: sin un 13 que anule el mismo documento de
 *       referencia, el reemplazo no tiene a qué sustituye.</li>
 *   <li>16 — re-emisión de un comprobante rechazado (Art. 19).</li>
 * </ul>
 *
 * <p><b>Orden de emisión exigido por los controles de este servicio:</b> cuando un
 * mismo documento anula con 13 y genera su reemplazo con 15, hay que persistir
 * <i>ambos</i> antes de enviar cualquiera de los dos. Así el control de
 * {@link #validarParejaAntesDeEnvio} encuentra la contraparte y no bloquea una
 * operación legítima.
 *
 * <p><b>Periodo.</b> No hay columna de periodo en el modelo: el periodo fiscal de
 * un comprobante es el mes de {@code encabezado.fechaEmision}, que es por donde
 * las declaraciones (D-104) filtran. Por eso la re-emisión conserva la fecha de
 * emisión del rechazado —además de ser lo que mantiene consistente la fecha que
 * ya viaja en las posiciones 4-9 de la clave— y {@link #exigirMismoPeriodo} lo
 * verifica: sin eso, el efecto contable caería en un periodo que no es el del
 * documento que se está modificando.
 */
@Named
@ApplicationScoped
public class SustitucionComprobanteService {

    private static final DateTimeFormatter PERIODO = DateTimeFormatter.ofPattern("yyyy-MM");

    /** Razon admite hasta 180 caracteres (XSD oficial v4.4). */
    private static final int MAX_LARGO_RAZON = 180;

    @Inject
    private @Nonnull ComprobantesEmitidosService comprobantesEmitidosService;

    // ─── Nota 9: construcción de la referencia ──────────────────────

    /**
     * Referencia de re-emisión por rechazo (Art. 19): el nuevo comprobante
     * apunta al rechazado con el código 16 "Sustituye comprobante electrónico
     * rechazado". No se genera ninguna nota de crédito.
     *
     * @param rechazado           comprobante con estado RECHAZADO en Hacienda
     * @param codigoDocumentoNuevo CodigoDocumento del comprobante que se emite
     * @param motivo              razón que viaja en {@code Razon} (máx. 180)
     */
    public @Nonnull InformacionReferencia referenciaReemisionPorRechazo(
            @Nonnull ComprobantesEmitidos rechazado,
            @Nullable String codigoDocumentoNuevo,
            @Nullable String motivo) {
        return InformacionReferencia.from(
                rechazado,
                Tipo_CodigosReferencia.SUSTITUYE_COMPROBANTE_ELECTRONICO_RECHAZADO,
                razon(motivo, "Re-emisión por rechazo de Hacienda (Art. 19). No corresponde nota de crédito."),
                codigoDocumentoNuevo);
    }

    /**
     * Referencia de anulación por error material (código 13). El documento de
     * reemplazo que se genere a partir de ella debe usar
     * {@link #referenciaSustitucionPorErrorMaterial}.
     */
    public @Nonnull InformacionReferencia referenciaAnulacionPorErrorMaterial(
            @Nonnull ComprobantesEmitidos documento,
            @Nullable String codigoDocumentoNuevo,
            @Nullable String motivo) {
        return InformacionReferencia.from(
                documento,
                Tipo_CodigosReferencia.ANULA_DOCUMENTO_REFERENCIA_ERROR_MATERIAL,
                razon(motivo, "Anulación por error material (código 13)."),
                codigoDocumentoNuevo);
    }

    /**
     * Referencia de sustitución por error material (código 15). Es la contraparte
     * obligatoria de un 13 sobre el mismo documento.
     */
    public @Nonnull InformacionReferencia referenciaSustitucionPorErrorMaterial(
            @Nonnull ComprobantesEmitidos documento,
            @Nullable String codigoDocumentoNuevo,
            @Nullable String motivo) {
        return InformacionReferencia.from(
                documento,
                Tipo_CodigosReferencia.SUSTITUYE_COMPROBANTE_ERROR_MATERIAL,
                razon(motivo, "Sustitución por error material (código 15)."),
                codigoDocumentoNuevo);
    }

    /** Razon nunca vacío (InformacionReferencia.from la exige) y dentro del tope. */
    private static @Nonnull String razon(@Nullable String motivo, @Nonnull String defecto) {
        String valor = motivo == null || motivo.isBlank() ? defecto : motivo.trim();
        return valor.length() > MAX_LARGO_RAZON ? valor.substring(0, MAX_LARGO_RAZON) : valor;
    }

    // ─── Periodo fiscal ─────────────────────────────────────────────

    /**
     * Periodo fiscal ("yyyy-MM") del comprobante, derivado de su fecha de
     * emisión. Es el mismo criterio con el que las declaraciones agrupan los
     * comprobantes, así que es el periodo donde debe caer el efecto contable.
     *
     * @return el periodo, o {@code null} si el comprobante no tiene encabezado o fecha
     */
    public @Nullable String periodo(@Nullable ComprobantesEmitidos comprobante) {
        if (comprobante == null || comprobante.getEncabezado() == null) {
            return null;
        }
        LocalDateTime fecha = comprobante.getEncabezado().getFechaEmision();
        return fecha == null ? null : PERIODO.format(fecha);
    }

    /**
     * Falla si el reemplazo no cae en el mismo periodo que el documento que
     * modifica. Los códigos 13 y 14 exigen que el efecto contable se reconozca en
     * el mismo periodo; la re-emisión de un rechazo arrastra la misma condición
     * porque es el mismo hecho económico.
     */
    public void exigirMismoPeriodo(@Nullable ComprobantesEmitidos original,
                                   @Nullable ComprobantesEmitidos reemplazo) {
        String periodoOriginal = periodo(original);
        String periodoReemplazo = periodo(reemplazo);
        if (periodoOriginal == null || periodoReemplazo == null) {
            return; // sin fecha no hay periodo que comparar: no se inventa nada
        }
        if (!periodoOriginal.equals(periodoReemplazo)) {
            throw new IllegalArgumentException(
                    "El reemplazo cae en el periodo " + periodoReemplazo
                    + " y el documento que modifica en el periodo " + periodoOriginal
                    + ". El efecto contable debe reconocerse en el mismo periodo "
                    + "que el documento rechazado o modificado.");
        }
    }

    // ─── Estado de rechazo ──────────────────────────────────────────

    /**
     * {@code true} si Hacienda rechazó el comprobante. Un comprobante rechazado no
     * tiene validez fiscal: no admite nota de crédito, se re-emite.
     */
    public boolean fueRechazadoPorHacienda(@Nullable ComprobantesEmitidos comprobante) {
        if (comprobante == null) {
            return false;
        }
        if ("RECHAZADO".equalsIgnoreCase(comprobante.getHaciendaEstado())) {
            return true;
        }
        return comprobante.getEncabezado() != null
                && "RECHAZADO".equalsIgnoreCase(comprobante.getEncabezado().getEstado());
    }

    // ── Existencia del sustituto de un rechazo ────────────────────

    /**
     * ¿Existe ya un comprobante que sustituya a otro con el código 16 "Sustituye
     * comprobante electrónico rechazado"? Art. 19: la re-emisión es un comprobante
     * NUEVO que referencia al rechazado con ese código, así que la referencia —y no
     * un contador de intentos— es lo que prueba que el rechazo ya quedó sustituido.
     *
     * <p>La ventana recibida es el <b>periodo fiscal completo</b> del rechazado, no
     * sólo su día de emisión: la re-emisión conserva su fecha a propósito —la clave
     * re-emitida repite las posiciones 4-9 del original y {@link #exigirMismoPeriodo}
     * exige además que el reemplazo caiga en el mismo periodo fiscal—, así que un
     * corte por día no puede ser la condición de reconocimiento: si el sustituto no
     * aparece el día exacto se reportaría como "no emitido" aunque ya exista.</p>
     *
     * <p>La comprobación corre <b>dentro de una transacción</b> a propósito. El bloque
     * {@code InformacionReferencia} es perezoso: leerlo sobre las entidades que
     * devuelve la consulta —ya desligadas de la sesión al terminar el método de
     * servicio— falla, y un chequeo de idempotencia que falla no es idempotencia.
     * Por eso este método abre la transacción, consulta e inspecciona, y devuelve un
     * {@code boolean} en vez de entidades: nadie toca la referencia fuera de ella.</p>
     *
     * <p>Los datos del rechazado llegan ya resueltos (id, consecutivo y ventana) y
     * no como entidad: una entidad desligada no puede devolver su encabezado perezoso
     * una vez iniciada la transacción.</p>
     *
     * @param idRechazado id del comprobante rechazado, que no cuenta como su propio
     *                    sustituto
     * @param consecutivo  Numero del rechazado al que debe apuntar el código 16
     * @param desde        inicio del periodo fiscal del rechazado
     * @param hasta        fin del periodo fiscal del rechazado
     * @return {@code true} si algún comprobante de la ventana lo sustituye con 16
     */
    @jakarta.transaction.Transactional
    public boolean existeSustitucionPorRechazo(@Nullable Long idRechazado,
                                                @Nonnull String consecutivo,
                                                @Nonnull Date desde,
                                                @Nonnull Date hasta) {
        List<ComprobantesEmitidos> periodo = comprobantesEmitidosService.listByDateRange(desde, hasta);
        if (periodo == null) {
            return false;
        }
        for (ComprobantesEmitidos candidato : periodo) {
            if (candidato == null
                    || candidato.getId() == null
                    || candidato.getId().equals(idRechazado)) {
                continue;
            }
            String sustituido = numeroReferenciadoConCodigo(candidato,
                    Tipo_CodigosReferencia.SUSTITUYE_COMPROBANTE_ELECTRONICO_RECHAZADO);
            if (consecutivo.equals(sustituido)) {
                return true;
            }
        }
        return false;
    }

    // ─── Control del emparejamiento 13 → 15 ─────────────────────────

    /**
     * El código 13 obliga a que el documento de reemplazo se emita con 15 sobre el
     * mismo documento de referencia. Esta comprobación es el punto donde el par no
     * puede romperse en silencio: si falta cualquiera de las dos mitades, falla.
     *
     * @param anulacion  documento que anula con el código 13
     * @param reemplazo  documento de reemplazo que se generó a partir de él
     * @throws IllegalArgumentException si la anulación no usa 13, el reemplazo no
     *                                  usa 15, o no se refieren al mismo documento
     */
    public void exigirParejaAnulacionReemplazo(@Nonnull ComprobantesEmitidos anulacion,
                                                @Nonnull ComprobantesEmitidos reemplazo) {
        String documentoAnulado = numeroReferenciadoConCodigo(anulacion,
                Tipo_CodigosReferencia.ANULA_DOCUMENTO_REFERENCIA_ERROR_MATERIAL);
        if (documentoAnulado == null) {
            throw new IllegalArgumentException(
                    "El documento de anulación no referencia ningún documento con el código 13 "
                    + "(anulación por error material), así que no puede emparejarse con un reemplazo.");
        }
        String documentoSustituido = numeroReferenciadoConCodigo(reemplazo,
                Tipo_CodigosReferencia.SUSTITUYE_COMPROBANTE_ERROR_MATERIAL);
        if (documentoSustituido == null) {
            throw new IllegalArgumentException(
                    "El documento de reemplazo no usa el código 15 (sustituye comprobante "
                    + "electrónico por error material). El código 13 sobre el documento "
                    + documentoAnulado + " obliga a que su reemplazo se emita con 15.");
        }
        if (!documentoAnulado.equals(documentoSustituido)) {
            throw new IllegalArgumentException(
                    "El par 13/15 no es consistente: la anulación con 13 referencia al documento "
                    + documentoAnulado + " y el reemplazo con 15 referencia al documento "
                    + documentoSustituido + ". Deben ser el mismo.");
        }
    }

    /**
     * Control previo al envío: un documento no puede ir a Hacienda si su bloque de
     * referencia rompe el emparejamiento 13 → 15.
     *
     * <ul>
     *   <li>Si usa 15, tiene que existir un comprobante emitido que anule con 13 el
     *       mismo documento de referencia.</li>
     *   <li>Si usa 13, tiene que existir ya su reemplazo con 15 (por eso ambos
     *       documentos se persisten antes de enviarse).</li>
     * </ul>
     *
     * @throws IllegalArgumentException con el detalle del incumplimiento
     */
    public void validarParejaAntesDeEnvio(@Nonnull ComprobantesEmitidos documento) {
        String anuladoPor15 = numeroReferenciadoConCodigo(documento,
                Tipo_CodigosReferencia.SUSTITUYE_COMPROBANTE_ERROR_MATERIAL);
        String anuladoPor13 = numeroReferenciadoConCodigo(documento,
                Tipo_CodigosReferencia.ANULA_DOCUMENTO_REFERENCIA_ERROR_MATERIAL);
        // Sin 13 ni 15 no hay nada que emparejar: no se toca la base.
        if (anuladoPor15 == null && anuladoPor13 == null) {
            return;
        }

        List<ComprobantesEmitidos> emitidos = emittedOrEmpty();

        if (anuladoPor15 != null && !existeAnulacion13(emitidos, anuladoPor15)) {
            throw new IllegalArgumentException(
                    "No se puede enviar: el comprobante usa el código 15 (sustituye comprobante "
                    + "electrónico por error material) sobre el documento " + anuladoPor15
                    + " pero no hay ninguna anulación con código 13 sobre ese mismo documento. "
                    + "Emita primero la anulación por error material.");
        }

        if (anuladoPor13 != null && !existeSustitucion15(emitidos, anuladoPor13)) {
            throw new IllegalArgumentException(
                    "No se puede enviar: la anulación con código 13 sobre el documento "
                    + anuladoPor13 + " no tiene su documento de reemplazo con código 15. "
                    + "Genere y persista el reemplazo antes de enviar la anulación.");
        }
    }

    private boolean existeAnulacion13(@Nonnull List<ComprobantesEmitidos> emitidos,
                                      @Nonnull String numeroDocumento) {
        for (ComprobantesEmitidos emitido : emitidos) {
            String referencia = numeroReferenciadoConCodigo(emitido,
                    Tipo_CodigosReferencia.ANULA_DOCUMENTO_REFERENCIA_ERROR_MATERIAL);
            if (numeroDocumento.equals(referencia)) {
                return true;
            }
        }
        return false;
    }

    private boolean existeSustitucion15(@Nonnull List<ComprobantesEmitidos> emitidos,
                                        @Nonnull String numeroDocumento) {
        for (ComprobantesEmitidos emitido : emitidos) {
            String referencia = numeroReferenciadoConCodigo(emitido,
                    Tipo_CodigosReferencia.SUSTITUYE_COMPROBANTE_ERROR_MATERIAL);
            if (numeroDocumento.equals(referencia)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Número del documento referenciado con el código de la nota 9 indicado, o
     * {@code null} si el comprobante no lo usa.
     */
    public @Nullable String numeroReferenciadoConCodigo(
            @Nullable ComprobantesEmitidos comprobante,
            @Nonnull Tipo_CodigosReferencia codigo) {
        if (comprobante == null || comprobante.getInformacionReferencia() == null) {
            return null;
        }
        for (InformacionReferencia ref : comprobante.getInformacionReferencia()) {
            if (ref != null && codigo.getCodigo().equals(ref.getCodigo()) && ref.getNumero() != null) {
                return ref.getNumero();
            }
        }
        return null;
    }

    private @Nonnull List<ComprobantesEmitidos> emittedOrEmpty() {
        List<ComprobantesEmitidos> emitidos = comprobantesEmitidosService.listAll();
        return emitidos == null ? new ArrayList<>() : emitidos;
    }
}
