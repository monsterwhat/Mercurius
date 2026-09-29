package Services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import Models.ComprobantesRecibidos;
import Models.Detalles.LineaDetalle;
import Models.Encabezado.Encabezado;
import Models.Resumen.ResumenFactura;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A rejected or undeliverable Mensaje Receptor must NOT be reported as sent.
 *
 * <p>Behavioral pin for the fix of {@code MensajeReceptorService}. Five paths
 * returned {@code MRResult(true, "...enviado a Hacienda.")} when nothing had
 * been delivered:</p>
 *
 * <ol>
 *   <li>XML generation failed — the message said "encolado" but no document
 *       existed.</li>
 *   <li>XAdES signing failed — same.</li>
 *   <li>A transport exception against Hacienda was caught and replaced with a
 *       fabricated {@code ApiResponse.ok("recibido")}.</li>
 *   <li>A {@code null} response was likewise replaced with {@code ok}.</li>
 *   <li>An explicit Hacienda REJECTION ran a branch byte-identical to the
 *       success branch, stamping the action state and reporting success. This
 *       is the worst of them: the business recorded invoices as notified by
 *       Hacienda that had never arrived.</li>
 * </ol>
 *
 * <p>Failures now return {@code success = false} and leave the invoice in
 * {@code PENDIENTE} — the state Consultas and the scheduler treat as "still to
 * notify" — instead of stamping the action and a receipt timestamp.</p>
 */
@QuarkusTest
@DisplayName("MensajeReceptorService: un rechazo no se reporta como enviado")
class MensajeReceptorServiceResultadoTest {

    @Inject MensajeReceptorService mensajeReceptorService;
    @Inject ComprobantesRecibidosService recibidosService;
    @Inject AppSettingsService appSettingsService;
    @InjectMock HaciendaApiService haciendaApiService;
    @InjectMock HaciendaSigner haciendaSigner;
    @InjectMock ComprobanteService comprobanteService;

    private ComprobantesRecibidos factura;

    @BeforeEach
    void prepararFactura() {
        if (appSettingsService.returnCurrent() == null) {
            appSettingsService.findOrCreateCurrent();
        }
        ConfiguracionConIdentificacion();

        Encabezado enc = new Encabezado();
        enc.setNumeroConsecutivo("0010000104" + String.format("%010d", Math.abs(hashCode())));
        enc.setFechaEmision(LocalDateTime.now());
        enc.setCondicionVenta("01");
        enc.setSchemaVersion("4.4");
        enc.setCodigoDocumento("01");
        enc.setClave("5060000000000000000000000000000000000000000000000" + (hashCode() % 10));

        ResumenFactura r = new ResumenFactura();
        r.setTotalVentaNeta(new BigDecimal("100"));
        r.setTotalImpuesto(new BigDecimal("13"));
        r.setTotalComprobante(new BigDecimal("113"));

        factura = new ComprobantesRecibidos();
        factura.setEncabezado(enc);
        factura.setResumen(r);
        factura.setStatus(true);
        factura.setProcessed(true);
        factura.setPaid(false);
        recibidosService.create(factura);
    }

    private void ConfiguracionConIdentificacion() {
        var s = appSettingsService.returnCurrent();
        if (s != null && (s.getIdentificacion() == null || s.getIdentificacion().isBlank())) {
            s.setIdentificacion("3100100008");
            s.setTipoIdentificacion("02");
            appSettingsService.update(s);
        }
    }

    private void stubFirmaCorrecta() {
        when(comprobanteService.generateMensajeReceptorXml(
                any(), anyString(), anyString(), anyString(), any(),
                any(Integer.class), anyString(), any(), any(), anyString()))
                .thenReturn("<MensajeReceptor/>");
        var ok = new HaciendaSigner.SignResult();
        ok.success = true;
        ok.signedXml = "<signed/>";
        when(haciendaSigner.signXml(anyString())).thenReturn(ok);
    }

    private MensajeReceptorService.MRResult enviar(int codigoMensaje, String accion) {
        return mensajeReceptorService.enviarMensajeReceptor(
                factura, codigoMensaje, accion,
                new BigDecimal("13"), new BigDecimal("113"));
    }

    private String estadoPersistido() {
        ComprobantesRecibidos leida = recibidosService.find(factura.getId());
        return leida == null ? null : leida.getHaciendaMensajeReceptorEstado();
    }

    @Test
    @DisplayName("un rechazo explicito de Hacienda no se reporta como enviado")
    void rechazoDeHaciendaNoEsExito() {
        stubFirmaCorrecta();
        when(haciendaApiService.acceptInvoice(any(), any(), any(), any(), any(), any()))
                .thenReturn(HaciendaApiService.ApiResponse.error(400, "El comprobante no cumple el formato"));

        MensajeReceptorService.MRResult r = enviar(1, "ACEPTAR");

        assertThat(r.success)
                .as("un rechazo explicito de Hacienda no puede ser success=true")
                .isFalse();
        assertThat(r.message)
                .as("el motivo real de Hacienda debe llegar al operador")
                .contains("rechazo")
                .contains("El comprobante no cumple el formato");
        assertThat(estadoPersistido())
                .as("la factura debe quedar PENDIENTE, no sellada como notificada")
                .isEqualTo("PENDIENTE");
    }

    @Test
    @DisplayName("un fallo de transporte deja la factura PENDIENTE")
    void falloDeTransporteNoEsExito() {
        stubFirmaCorrecta();
        when(haciendaApiService.acceptInvoice(any(), any(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("Connection reset"));

        MensajeReceptorService.MRResult r = enviar(1, "ACEPTAR");

        assertThat(r.success).as("un timeout jamas es un envio correcto").isFalse();
        assertThat(r.message).contains("No se pudo comunicar con Hacienda");
        assertThat(estadoPersistido()).isEqualTo("PENDIENTE");
    }

    @Test
    @DisplayName("una respuesta nula de Hacienda no se convierte en ok")
    void respuestaNulaNoEsExito() {
        stubFirmaCorrecta();
        when(haciendaApiService.acceptInvoice(any(), any(), any(), any(), any(), any()))
                .thenReturn(null);

        MensajeReceptorService.MRResult r = enviar(1, "ACEPTAR");

        assertThat(r.success).as("una respuesta nula no es exito").isFalse();
        assertThat(r.message).contains("no devolvio respuesta");
        assertThat(estadoPersistido()).isEqualTo("PENDIENTE");
    }

    @Test
    @DisplayName("un fallo de generacion de XML no se reporta como encolado")
    void falloDeGeneracionXmlNoEsExito() {
        when(comprobanteService.generateMensajeReceptorXml(
                any(), anyString(), anyString(), anyString(), any(),
                any(Integer.class), anyString(), any(), any(), anyString()))
                .thenReturn(null);
        verifyNoInteractions(haciendaApiService);

        MensajeReceptorService.MRResult r = enviar(1, "ACEPTAR");

        assertThat(r.success).as("sin XML no hay nada que enviar").isFalse();
        assertThat(r.message)
                .as("antes decia 'Mensaje Receptor encolado' sin haber encolado nada")
                .contains("No se pudo generar el XML")
                .doesNotContain("encolado");
        assertThat(estadoPersistido()).isEqualTo("PENDIENTE");
    }

    @Test
    @DisplayName("un fallo de firma XAdES no se reporta como encolado")
    void falloDeFirmaNoEsExito() {
        when(comprobanteService.generateMensajeReceptorXml(
                any(), anyString(), anyString(), anyString(), any(),
                any(Integer.class), anyString(), any(), any(), anyString()))
                .thenReturn("<MensajeReceptor/>");
        var fallo = new HaciendaSigner.SignResult();
        fallo.success = false;
        when(haciendaSigner.signXml(anyString())).thenReturn(fallo);

        MensajeReceptorService.MRResult r = enviar(1, "ACEPTAR");

        assertThat(r.success).as("una firma fallida no es un envio").isFalse();
        assertThat(r.message)
                .as("una firma fallida no es un envio encolado")
                .contains("No se pudo firmar el XML")
                .doesNotContain("encolado");
        assertThat(estadoPersistido()).isEqualTo("PENDIENTE");
    }

    @Test
    @DisplayName("el camino feliz sigue sellando el estado de la accion")
    void exitoSigueSellandoElEstado() {
        stubFirmaCorrecta();
        when(haciendaApiService.acceptInvoice(any(), any(), any(), any(), any(), any()))
                .thenReturn(HaciendaApiService.ApiResponse.ok("ok"));

        MensajeReceptorService.MRResult r = enviar(1, "ACEPTAR");

        assertThat(r.success).as("el camino feliz no debe cambiar").isTrue();
        assertThat(r.message).contains("enviado a Hacienda");
        assertThat(estadoPersistido())
                .as("el contrato de exito no cambia: se guarda la accion realizada")
                .isEqualTo("ACEPTAR");
    }
}
