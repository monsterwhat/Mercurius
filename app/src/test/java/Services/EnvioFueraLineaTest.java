package Services;

import Models.ComprobantesEmitidos;
import Models.ConfiguracionAplicacion;
import Models.Encabezado.Encabezado;
import Models.EnvioFueraLinea;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the offline emission outbox behind CR Art. 21 ¶3 (Ley 6828):
 * a document whose signed XML cannot be transmitted at the point of sale must be
 * generated and signed there, sent no later than two business days later, and
 * escalated when that is impossible.
 *
 * <p>Pure Mockito test — no Quarkus boot, no database, no network. The
 * {@link EntityManager} is a mock and the in-memory {@link EnvioFueraLinea} row
 * is inspected directly, so the assertions are on the exact state the service
 * persists. {@code LocalDateTime} is injected into every method that needs
 * "now", so nothing here depends on the wall clock.
 *
 * <p>The connectivity probe is exercised only on its fail-open branch: the
 * offline branch opens a real socket to Hacienda and is therefore not
 * deterministic in a unit test.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EnvioFueraLineaTest {

    @Mock private EntityManager em;
    @Mock private TypedQuery<EnvioFueraLinea> queryPorClave;
    @Mock private AppSettingsService appSettingsService;
    @Mock private HaciendaApiService haciendaApiService;
    @Mock private EmailService emailService;
    @Mock private EnvioFueraLineaService envioFueraLineaService;

    @InjectMocks
    private EnvioFueraLineaService service;

    // ── esDiaHabil ────────────────────────────────────────────────────────

    @Test
    void soloLunesAViernesSonDiasHabiles() {
        // 2026-09-21 is a Monday.
        assertThat(EnvioFueraLineaService.esDiaHabil(LocalDate.of(2026, 9, 21))).isTrue();
        assertThat(EnvioFueraLineaService.esDiaHabil(LocalDate.of(2026, 9, 25))).isTrue();
        assertThat(EnvioFueraLineaService.esDiaHabil(LocalDate.of(2026, 9, 26))).isFalse();
        assertThat(EnvioFueraLineaService.esDiaHabil(LocalDate.of(2026, 9, 27))).isFalse();
    }

    // ── calcularVencimiento: the two-business-day deadline ─────────────────

    @Test
    void lunesSeVenceElMiercoles() {
        assertThat(EnvioFueraLineaService.calcularVencimiento(
                LocalDateTime.of(2026, 9, 21, 10, 0)))
                .isEqualTo(LocalDateTime.of(2026, 9, 23, 10, 0));
    }

    @Test
    void viernesSaltaElFinDeSemanaYSeVenceElMartes() {
        // Fri 10:00 + Sat(no) + Sun(no) + Mon(1) + Tue(2) = Tue 10:00.
        assertThat(EnvioFueraLineaService.calcularVencimiento(
                LocalDateTime.of(2026, 9, 25, 10, 0)))
                .isEqualTo(LocalDateTime.of(2026, 9, 29, 10, 0));
    }

    @Test
    void sabadoYDomingoSeVencenElMartes() {
        assertThat(EnvioFueraLineaService.calcularVencimiento(
                LocalDateTime.of(2026, 9, 26, 9, 15)))
                .isEqualTo(LocalDateTime.of(2026, 9, 29, 9, 15));
        assertThat(EnvioFueraLineaService.calcularVencimiento(
                LocalDateTime.of(2026, 9, 27, 23, 0)))
                .isEqualTo(LocalDateTime.of(2026, 9, 29, 23, 0));
    }

    @Test
    void elVencimientoNuncaCaeEnFinDeSemanaYSiempreEstaEnElFuturo() {
        LocalDateTime desde = LocalDateTime.of(2026, 9, 21, 0, 0);
        for (int i = 0; i < 7; i++) {
            LocalDateTime emision = desde.plusDays(i);
            LocalDateTime vencimiento = EnvioFueraLineaService.calcularVencimiento(emision);
            assertThat(EnvioFueraLineaService.esDiaHabil(vencimiento.toLocalDate()))
                    .as("vencimiento de una venta del %s", emision.toLocalDate())
                    .isTrue();
            assertThat(vencimiento).isAfter(emision);
            // Nunca mas de 4 dias naturales por delante, aun cruzando un fin
            // de semana completo. El limite es INCLUSIVO, no estricto: una venta
            // del jueves vence el lunes siguiente (jue+vie como habiles 1 y 2,
            // sab+dom saltados), exactamente emision.plusDays(4). La version
            // anterior usaba isBefore(plusDays(4)) y fallaba en ese limite.
            assertThat(vencimiento).isBeforeOrEqualTo(emision.plusDays(4));
        }
    }

    @Test
    void elPlazoEsDeDosDiasHabiles() {
        assertThat(EnvioFueraLineaService.PLAZO_DIAS_HABILES).isEqualTo(2);
    }

    // ── calcularProximoIntento: backoff clamped by the deadline ────────────

    @Test
    void elBackoffCreceYSeAcotaAlVencimiento() {
        LocalDateTime base = LocalDateTime.of(2026, 9, 21, 10, 0);
        LocalDateTime vencimiento = base.plusDays(2);

        assertThat(EnvioFueraLineaService.calcularProximoIntento(base, 1, vencimiento))
                .isEqualTo(base.plusMinutes(15));
        assertThat(EnvioFueraLineaService.calcularProximoIntento(base, 2, vencimiento))
                .isEqualTo(base.plusHours(1));
        assertThat(EnvioFueraLineaService.calcularProximoIntento(base, 3, vencimiento))
                .isAfter(EnvioFueraLineaService.calcularProximoIntento(base, 2, vencimiento));
        // A deadline only 10 minutes away caps the first backoff at the deadline.
        assertThat(EnvioFueraLineaService.calcularProximoIntento(base, 1, base.plusMinutes(10)))
                .isEqualTo(base.plusMinutes(10));
        // The backoff is strictly increasing, so it never retries in a tight loop.
        assertThat(EnvioFueraLineaService.calcularProximoIntento(base, 4, vencimiento))
                .isAfter(EnvioFueraLineaService.calcularProximoIntento(base, 3, vencimiento));
    }

    // ── Connectivity probe: fail-open branch only ─────────────────────────

    @Test
    void elSondeoFallaAbiertoSiNoSePuedeLeerLaConfiguracion() {
        // "Unknown host" is not "offline": a config read failure must not push every
        // sale onto the situacion 3 path.
        when(appSettingsService.returnCurrent()).thenThrow(new IllegalStateException("sin configuracion"));

        assertThat(service.hayConectividadConHacienda()).isTrue();
    }

    // ── registrarDocumentoFirmado ──────────────────────────────────────────

    @Test
    void encolaElDocumentoFirmadoConSituacion3YPlazoDeDosDiasHabiles() {
        prepararConsultaPorClave(null);
        LocalDateTime emision = LocalDateTime.of(2026, 9, 25, 16, 30); // Friday
        ComprobantesEmitidos comprobante = comprobanteConClave(claveConSituacion(3), emision);
        when(appSettingsService.returnCurrent()).thenReturn(configuracion());

        EnvioFueraLinea registro = service.registrarDocumentoFirmado(
                comprobante, "04", "001", "00001",
                EnvioFueraLineaService.SITUACION_FUERA_LINEA,
                "<TiqueteElectronico>firma</TiqueteElectronico>",
                EnvioFueraLineaService.ORIGEN_OFFLINE_SITUACION_3);

        assertThat(registro).isNotNull();
        assertThat(registro.getTipoDocumento()).isEqualTo("04");
        assertThat(registro.getClave()).isEqualTo(comprobante.getHaciendaClave());
        assertThat(registro.getConsecutivo()).isEqualTo("0010000104000000042");
        assertThat(registro.getSucursal()).isEqualTo("001");
        assertThat(registro.getTerminal()).isEqualTo("00001");
        assertThat(registro.getSituacion()).isEqualTo("3");
        assertThat(registro.getEstado()).isEqualTo(EnvioFueraLineaService.ESTADO_PENDIENTE);
        assertThat(registro.getIntentos()).isZero();
        assertThat(registro.getOrigen()).isEqualTo(EnvioFueraLineaService.ORIGEN_OFFLINE_SITUACION_3);
        assertThat(registro.getComprobanteId()).isEqualTo(7L);
        // Fri 16:30 -> Sat(no) -> Sun(no) -> Mon(1) -> Tue(2) = Tue 16:30.
        assertThat(registro.getVencimiento()).isEqualTo(LocalDateTime.of(2026, 9, 29, 16, 30));
        assertThat(registro.getProximoIntento()).isNotNull();
        // The exact signed payload is what will be transmitted, not a re-signed copy.
        assertThat(registro.getXmlFirmado()).isEqualTo("<TiqueteElectronico>firma</TiqueteElectronico>");
        assertThat(registro.getXmlFirmadoBytes())
                .containsExactly("<TiqueteElectronico>firma</TiqueteElectronico>".getBytes(StandardCharsets.UTF_8));

        ArgumentCaptor<EnvioFueraLinea> captor = ArgumentCaptor.forClass(EnvioFueraLinea.class);
        verify(em).persist(captor.capture());
        verify(em).flush();
        assertThat(captor.getValue().getClave()).isEqualTo(comprobante.getHaciendaClave());
    }

    @Test
    void elSobreDeTransmisionQuedaCongeladoEnElMomentoDeLaFirma() {
        prepararConsultaPorClave(null);
        ComprobantesEmitidos comprobante = comprobanteConClave(claveConSituacion(3),
                LocalDateTime.of(2026, 9, 21, 10, 0));
        ConfiguracionAplicacion settings = configuracion();
        settings.setTipoIdentificacion("02");
        settings.setIdentificacion("310101123456");
        when(appSettingsService.returnCurrent()).thenReturn(settings);

        EnvioFueraLinea registro = service.registrarDocumentoFirmado(
                comprobante, "01", "001", "00001",
                EnvioFueraLineaService.SITUACION_FUERA_LINEA, "<FacturaElectronica/>",
                EnvioFueraLineaService.ORIGEN_OFFLINE_SITUACION_3);

        assertThat(registro.getEmisorTipoId()).isEqualTo("02");
        assertThat(registro.getEmisorNumeroId()).isEqualTo("310101123456");
        // No receptor on the document: the facade's anonymous default is used,
        // never a null that would blow up inside the Hacienda client.
        assertThat(registro.getReceptorTipoId()).isEqualTo("01");
        assertThat(registro.getReceptorNumeroId()).isEqualTo("000000000");
    }

    @Test
    void encolarDosVecesLaMismaClaveNoDuplicaLaFila() {
        EnvioFueraLinea existente = new EnvioFueraLinea();
        existente.setClave(claveConSituacion(3));
        existente.setEstado(EnvioFueraLineaService.ESTADO_PENDIENTE);
        prepararConsultaPorClave(existente);
        when(appSettingsService.returnCurrent()).thenReturn(configuracion());

        EnvioFueraLinea resultado = service.registrarDocumentoFirmado(
                comprobanteConClave(claveConSituacion(3), LocalDateTime.now()),
                "04", "001", "00001",
                EnvioFueraLineaService.SITUACION_FUERA_LINEA, "<x/>",
                EnvioFueraLineaService.ORIGEN_OFFLINE_SITUACION_3);

        assertThat(resultado).isSameAs(existente);
        verify(em, never()).persist(any(EnvioFueraLinea.class));
    }

    @Test
    void unDocumentoSinClaveNoSeEncola() {
        ComprobantesEmitidos comprobante = comprobanteConClave(null, LocalDateTime.now());

        assertThat(service.registrarDocumentoFirmado(
                comprobante, "04", "001", "00001",
                EnvioFueraLineaService.SITUACION_FUERA_LINEA, "<x/>",
                EnvioFueraLineaService.ORIGEN_OFFLINE_SITUACION_3)).isNull();

        verify(em, never()).persist(any(EnvioFueraLinea.class));
    }

    // ── reintentar: happy path ─────────────────────────────────────────────

    @Test
    void unReintentoExitosoEnviaLosBytesFirmadosYCierraElComprobante() {
        EnvioFueraLinea registro = registroPendiente(0);
        registro.setComprobanteId(7L);
        registro.setEmisorTipoId("02");
        registro.setEmisorNumeroId("310101123456");
        registro.setReceptorTipoId("01");
        registro.setReceptorNumeroId("000000000");
        when(haciendaApiService.submitAndWait(anyString(), anyString(), any(), any(), any(), any()))
                .thenReturn(HaciendaApiService.ApiResponse.ok("{\"indEstado\":\"ACEPTADO\"}"));
        ComprobantesEmitidos comprobante = new ComprobantesEmitidos();
        comprobante.setId(7L);
        when(em.find(ComprobantesEmitidos.class, 7L)).thenReturn(comprobante);

        LocalDateTime ahora = LocalDateTime.of(2026, 9, 21, 12, 0);
        EnvioFueraLineaService.ResultadoReintento resultado = service.reintentar(registro, ahora);

        assertThat(resultado.enviado).isTrue();
        assertThat(resultado.escalado).isFalse();
        assertThat(registro.getEstado()).isEqualTo(EnvioFueraLineaService.ESTADO_ENVIADO);
        assertThat(registro.getIntentos()).isEqualTo(1);
        assertThat(registro.getFechaEnvio()).isEqualTo(ahora);
        assertThat(registro.getProximoIntento()).isNull();
        assertThat(registro.getUltimoError()).isNull();
        // The stored payload is replayed verbatim: no re-marshalling, no re-signing.
        verify(haciendaApiService).submitAndWait(
                eq(registro.getClave()), eq("<x/>"),
                eq("02"), eq("310101123456"), eq("01"), eq("000000000"));
        assertThat(comprobante.getHaciendaEstado()).isEqualTo("ACEPTADO");
    }

    // ── reintentar: transient failure reschedules ──────────────────────────

    @Test
    void unFalloTransitorioSumaUnIntentoYProgramaElSiguiente() {
        EnvioFueraLinea registro = registroPendiente(0);
        when(haciendaApiService.submitAndWait(anyString(), anyString(), any(), any(), any(), any()))
                .thenReturn(HaciendaApiService.ApiResponse.error(503, "sin conectividad"));

        LocalDateTime ahora = LocalDateTime.of(2026, 9, 21, 12, 0);
        EnvioFueraLineaService.ResultadoReintento resultado = service.reintentar(registro, ahora);

        assertThat(resultado.enviado).isFalse();
        assertThat(resultado.escalado).isFalse();
        assertThat(registro.getEstado()).isEqualTo(EnvioFueraLineaService.ESTADO_PENDIENTE);
        assertThat(registro.getIntentos()).isEqualTo(1);
        assertThat(registro.getUltimoError()).isEqualTo("sin conectividad");
        assertThat(registro.getProximoIntento()).isEqualTo(ahora.plusMinutes(15));
    }

    @Test
    void unaExcepcionDeTransporteCuentaComoIntentoFallidoYNoPropaga() {
        EnvioFueraLinea registro = registroPendiente(0);
        when(haciendaApiService.submitAndWait(anyString(), anyString(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("connection reset"));

        LocalDateTime ahora = LocalDateTime.of(2026, 9, 21, 12, 0);
        EnvioFueraLineaService.ResultadoReintento resultado = service.reintentar(registro, ahora);

        assertThat(resultado.enviado).isFalse();
        assertThat(resultado.escalado).isFalse();
        assertThat(registro.getIntentos()).isEqualTo(1);
        assertThat(registro.getUltimoError()).contains("connection reset");
    }

    // ── reintentar: exhausted attempts and missed deadline ─────────────────

    @Test
    void alAgotarLosIntentosElDocumentoEscalaAVencido() {
        EnvioFueraLinea registro = registroPendiente(EnvioFueraLineaService.MAX_INTENTOS - 1);
        when(haciendaApiService.submitAndWait(anyString(), anyString(), any(), any(), any(), any()))
                .thenReturn(HaciendaApiService.ApiResponse.error(503, "sin conectividad"));

        LocalDateTime ahora = LocalDateTime.of(2026, 9, 21, 12, 0);
        EnvioFueraLineaService.ResultadoReintento resultado = service.reintentar(registro, ahora);

        assertThat(resultado.escalado).isTrue();
        assertThat(registro.getEstado()).isEqualTo(EnvioFueraLineaService.ESTADO_VENCIDO);
        assertThat(registro.getIntentos()).isEqualTo(EnvioFueraLineaService.MAX_INTENTOS);
        assertThat(registro.getProximoIntento()).isNull();
        assertThat(registro.getUltimoError()).contains("Se agotaron los");
        // No further transmission is scheduled once escalated.
        verify(em, times(1)).merge(any(EnvioFueraLinea.class));
    }

    @Test
    void alVencerElPlazoSeEscalaSinTocarLaRed() {
        EnvioFueraLinea registro = registroPendiente(0);
        LocalDateTime ahora = registro.getVencimiento();

        EnvioFueraLineaService.ResultadoReintento resultado = service.reintentar(registro, ahora);

        assertThat(resultado.escalado).isTrue();
        assertThat(resultado.enviado).isFalse();
        assertThat(registro.getEstado()).isEqualTo(EnvioFueraLineaService.ESTADO_VENCIDO);
        assertThat(registro.getIntentos()).isZero();
        assertThat(registro.getUltimoError()).contains("Art. 21 parrafo 3");
        // A document past its legal deadline is reported as a breach, never
        // quietly transmitted outside the window.
        verify(haciendaApiService, never())
                .submitAndWait(anyString(), anyString(), any(), any(), any(), any());
    }

    @Test
    void unDocumentoYaTransmitidoNoSeReintenta() {
        EnvioFueraLinea registro = registroPendiente(1);
        registro.setEstado(EnvioFueraLineaService.ESTADO_ENVIADO);

        EnvioFueraLineaService.ResultadoReintento resultado = service.reintentar(
                registro, LocalDateTime.of(2026, 9, 21, 12, 0));

        assertThat(resultado.omitido).isTrue();
        assertThat(resultado.enviado).isFalse();
        assertThat(registro.getIntentos()).isEqualTo(1);
        verify(haciendaApiService, never())
                .submitAndWait(anyString(), anyString(), any(), any(), any(), any());
    }

    @Test
    void unDocumentoQueAunNoLeTocaNoSeReintenta() {
        EnvioFueraLinea registro = registroPendiente(0);
        registro.setProximoIntento(LocalDateTime.of(2026, 9, 21, 18, 0));

        EnvioFueraLineaService.ResultadoReintento resultado = service.reintentar(
                registro, LocalDateTime.of(2026, 9, 21, 12, 0));

        assertThat(resultado.omitido).isTrue();
        verify(haciendaApiService, never())
                .submitAndWait(anyString(), anyString(), any(), any(), any(), any());
    }

    // ── Scheduler job: never lets a failure reach the scheduler ────────────

    @Test
    void elJobNoPropagaRuntimeExceptionNiError() {
        EnvioFueraLineaRetryJob job = new EnvioFueraLineaRetryJob();
        inject(job, envioFueraLineaService);

        when(envioFueraLineaService.procesarPendientes())
                .thenThrow(new IllegalStateException("db caida"));
        assertThatCode(job::reintentarDocumentosPendientes).doesNotThrowAnyException();

        // doThrow, no when: el stub anterior ya hace que procesarPendientes()
        // lance IllegalStateException, asi que un segundo when(...) invocaria el
        // metodo y lanzaria ANTES de que thenThrow se ejecute, dejando activo el
        // stub viejo. doThrow evita la invocacion y reemplaza el stub de verdad.
        org.mockito.Mockito.doThrow(new StackOverflowError())
                .when(envioFueraLineaService).procesarPendientes();
        assertThatCode(job::reintentarDocumentosPendientes).doesNotThrowAnyException();
    }

    // ── Fixtures ───────────────────────────────────────────────────────────

    private void inject(Object target, Object value) {
        try {
            java.lang.reflect.Field field =
                    EnvioFueraLineaRetryJob.class.getDeclaredField("envioFueraLineaService");
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("no se pudo inyectar el servicio en el job", e);
        }
    }

    private void prepararConsultaPorClave(EnvioFueraLinea resultado) {
        when(em.createQuery(anyString(), eq(EnvioFueraLinea.class))).thenReturn(queryPorClave);
        when(queryPorClave.setParameter(anyString(), any())).thenReturn(queryPorClave);
        when(queryPorClave.setMaxResults(anyInt())).thenReturn(queryPorClave);
        when(queryPorClave.getSingleResultOrNull()).thenReturn(resultado);
    }

    private ConfiguracionAplicacion configuracion() {
        ConfiguracionAplicacion settings = new ConfiguracionAplicacion();
        settings.setTipoIdentificacion("02");
        settings.setIdentificacion("310101123456");
        settings.setHaciendaEnvironment("sandbox");
        return settings;
    }

    private ComprobantesEmitidos comprobanteConClave(String clave, LocalDateTime fechaEmision) {
        Encabezado encabezado = new Encabezado();
        encabezado.setNumeroConsecutivo("0010000104000000042");
        encabezado.setFechaEmision(fechaEmision);
        encabezado.setCodigoDocumento("04");
        ComprobantesEmitidos comprobante = new ComprobantesEmitidos();
        comprobante.setId(7L);
        comprobante.setEncabezado(encabezado);
        comprobante.setHaciendaClave(clave);
        return comprobante;
    }

    /**
     * A pending row due now, with a deadline two business days out and room for
     * several more backoffs. The clave carries Situacion 3 at position 42
     * (index 41) exactly as HaciendaSigner writes it.
     */
    private EnvioFueraLinea registroPendiente(int intentos) {
        EnvioFueraLinea registro = new EnvioFueraLinea();
        registro.setId(1L);
        registro.setTipoDocumento("04");
        registro.setClave(claveConSituacion(3));
        registro.setConsecutivo("0010000104000000042");
        registro.setSucursal("001");
        registro.setTerminal("00001");
        registro.setSituacion(EnvioFueraLineaService.SITUACION_FUERA_LINEA);
        registro.setXmlFirmado("<x/>");
        registro.setXmlFirmadoBytes("<x/>".getBytes(StandardCharsets.UTF_8));
        registro.setIntentos(intentos);
        registro.setEstado(EnvioFueraLineaService.ESTADO_PENDIENTE);
        registro.setOrigen(EnvioFueraLineaService.ORIGEN_OFFLINE_SITUACION_3);
        registro.setFechaEmision(LocalDateTime.of(2026, 9, 21, 10, 0));
        registro.setFechaCreacion(LocalDateTime.of(2026, 9, 21, 10, 0));
        registro.setProximoIntento(LocalDateTime.of(2026, 9, 21, 12, 0));
        registro.setVencimiento(LocalDateTime.of(2026, 9, 23, 10, 0));
        return registro;
    }

    /** 50-char clave shape: 506 + DDMMYY + 12 emisor + 20 consecutivo + situacion + 8. */
    private String claveConSituacion(int situacion) {
        return "506210926310101123456001000010400000042" + situacion + "12345678";
    }
}
