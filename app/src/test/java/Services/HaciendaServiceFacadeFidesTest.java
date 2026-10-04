package Services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import Models.ComprobantesEmitidos;
import Models.ConfiguracionAplicacion;
import Models.Detalles.DetalleServicio;
import Models.Detalles.Impuesto;
import Models.Detalles.LineaDetalle;
import Models.Encabezado.Encabezado;
import Models.Encabezado.IdentificacionReceptor;
import Models.Encabezado.Receptor;
import Models.Resumen.ResumenFactura;
import Services.Strategies.DocumentoStrategyFactory;

/**
 * Rama Fides de {@link HaciendaServiceFacade}: sin Fides corriendo en local
 * (puerto 8080 caído) estos tests fijan el contrato Mercurius→Fides con mocks.
 *
 * <p>Test puro Mockito — sin boot Quarkus, sin base de datos, sin red. Cubre:
 * el mapeo {@code ComprobantesEmitidos → FidesApiService.InvoiceData}, el
 * enrutado por {@code useFides} y la traducción de respuestas Fides
 * (ok/rechazo/excepción) a {@code SubmitResult}.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("HaciendaServiceFacade: rama Fides")
class HaciendaServiceFacadeFidesTest {

    @Mock private AppSettingsService appSettingsService;
    @Mock private FidesApiService fidesApiService;
    @Mock private HaciendaApiService haciendaApiService;
    @Mock private HaciendaSigner haciendaSigner;
    @Mock private DocumentoStrategyFactory strategyFactory;

    @InjectMocks
    private HaciendaServiceFacade facade;

    private static ConfiguracionAplicacion ajustes(boolean fides) {
        ConfiguracionAplicacion s = new ConfiguracionAplicacion();
        s.setUseFides(fides);
        s.setIdentificacion("3101123456");
        s.setNombreNegocio("Negocio Demo");
        s.setFidesApiUrl("http://localhost:8080");
        return s;
    }

    private static ComprobantesEmitidos comprobanteCompleto() {
        IdentificacionReceptor idReceptor = new IdentificacionReceptor();
        idReceptor.setTipo("01");
        idReceptor.setNumero("109990001");

        Receptor receptor = new Receptor();
        receptor.setNombre("Juan Perez");
        receptor.setIdentificacion(idReceptor);

        Encabezado encabezado = new Encabezado();
        encabezado.setReceptor(receptor);
        encabezado.setNumeroConsecutivo("00100001010000000001");
        encabezado.setCondicionVenta("02");
        encabezado.setPlazoCredito("30");
        encabezado.setCodigoActividadEmisor("123456");

        Impuesto impuesto = new Impuesto();
        impuesto.setTarifa(new BigDecimal("13.00"));

        LineaDetalle linea = new LineaDetalle();
        linea.setCodigoCabys("1234567890123");
        linea.setDetalle("Cafe molido 500g");
        linea.setCantidad(new BigDecimal("2"));
        linea.setPrecioUnitario(new BigDecimal("1500"));
        linea.setImpuestos(List.of(impuesto));

        DetalleServicio detalles = new DetalleServicio();
        detalles.setLineasDetalle(List.of(linea));

        ResumenFactura resumen = new ResumenFactura();
        resumen.setTotalComprobante(new BigDecimal("3390"));

        ComprobantesEmitidos c = new ComprobantesEmitidos();
        c.setHaciendaClave("50602102500003101123456001000000010000000011");
        c.setEncabezado(encabezado);
        c.setDetalles(detalles);
        c.setResumen(resumen);
        return c;
    }

    @Test
    @DisplayName("Fides ok se traduce a ACEPTADO")
    void fidesOkEsAceptado() {
        when(appSettingsService.returnCurrent()).thenReturn(ajustes(true));
        when(fidesApiService.submitToHaciendaViaFides(any()))
                .thenReturn(FidesApiService.FidesResponse.ok(200, "{\"estado\":\"ACEPTADO\"}"));

        HaciendaServiceFacade.SubmitResult r = facade.submitDocument(new ComprobantesEmitidos());

        assertThat(r.success).isTrue();
        assertThat(r.estado).isEqualTo("ACEPTADO");
        verify(fidesApiService).submitToHaciendaViaFides(any(FidesApiService.InvoiceData.class));
    }

    @Test
    @DisplayName("Fides error se traduce a RECHAZADO con el mensaje original")
    void fidesErrorEsRechazado() {
        when(appSettingsService.returnCurrent()).thenReturn(ajustes(true));
        when(fidesApiService.submitToHaciendaViaFides(any()))
                .thenReturn(FidesApiService.FidesResponse.error(400, "RUC receptor invalido"));

        HaciendaServiceFacade.SubmitResult r = facade.submitDocument(new ComprobantesEmitidos());

        assertThat(r.success).isFalse();
        assertThat(r.estado).isEqualTo("RECHAZADO");
        assertThat(r.errorMessage).contains("RUC receptor invalido");
    }

    @Test
    @DisplayName("Fides que lanza se traduce a ERROR sin propagar")
    void fidesExcepcionEsError() {
        when(appSettingsService.returnCurrent()).thenReturn(ajustes(true));
        when(fidesApiService.submitToHaciendaViaFides(any()))
                .thenThrow(new RuntimeException("timeout contra Fides"));

        HaciendaServiceFacade.SubmitResult r = facade.submitDocument(new ComprobantesEmitidos());

        assertThat(r.success).isFalse();
        assertThat(r.estado).isEqualTo("ERROR");
        assertThat(r.errorMessage).contains("Fides");
    }

    @Test
    @DisplayName("Sin clave en ajustes (null) se usa la vía directa, Fides intacto")
    void sinAjustesUsaViaDirecta() {
        when(appSettingsService.returnCurrent()).thenReturn(null);

        HaciendaServiceFacade.SubmitResult r = facade.submitDocument(new ComprobantesEmitidos());

        assertThat(r.success).isFalse();
        verifyNoInteractions(fidesApiService);
    }

    @Test
    @DisplayName("useFides=false no toca Fides (vía directa, sin clave = error local)")
    void deshabilitadoNoTocaFides() {
        when(appSettingsService.returnCurrent()).thenReturn(ajustes(false));

        HaciendaServiceFacade.SubmitResult r = facade.submitDocument(new ComprobantesEmitidos());

        assertThat(r.success).isFalse();
        assertThat(r.errorMessage).isEqualTo("Comprobante sin clave de Hacienda");
        verify(fidesApiService, never()).submitToHaciendaViaFides(any());
    }

    @Test
    @DisplayName("El DTO que viaja a Fides lleva emisor, receptor, líneas y total")
    void mapeaCamposAlDtoDeFides() {
        when(appSettingsService.returnCurrent()).thenReturn(ajustes(true));
        when(fidesApiService.submitToHaciendaViaFides(any()))
                .thenReturn(FidesApiService.FidesResponse.ok(200, "{\"estado\":\"ACEPTADO\"}"));

        facade.submitDocument(comprobanteCompleto());

        ArgumentCaptor<FidesApiService.InvoiceData> captor =
                ArgumentCaptor.forClass(FidesApiService.InvoiceData.class);
        verify(fidesApiService).submitToHaciendaViaFides(captor.capture());
        FidesApiService.InvoiceData dto = captor.getValue();

        assertThat(dto.issuerTaxId).isEqualTo("3101123456");
        assertThat(dto.issuerName).isEqualTo("Negocio Demo");
        assertThat(dto.receiverTaxId).isEqualTo("109990001");
        assertThat(dto.receiverName).isEqualTo("Juan Perez");
        assertThat(dto.accessKey).isEqualTo("50602102500003101123456001000000010000000011");
        assertThat(dto.total).isEqualTo("3390");
        assertThat(dto.items).hasSize(1);
        assertThat(dto.items.get(0).code).isEqualTo("1234567890123");
        assertThat(dto.items.get(0).description).isEqualTo("Cafe molido 500g");
        assertThat(dto.items.get(0).quantity).isEqualTo("2");
        assertThat(dto.items.get(0).unitPrice).isEqualTo("1500");
        assertThat(dto.items.get(0).taxRate).isEqualTo("13.00");
        assertThat(dto.consecutivo).isEqualTo("00100001010000000001");
        assertThat(dto.condicionVenta).isEqualTo("02");
        assertThat(dto.plazoCredito).isEqualTo(30);
        assertThat(dto.codigoActividadEmisor).isEqualTo("123456");
    }

    @Test
    @DisplayName("Clave radicada distinta a la enviada se rechaza, no se marca ACEPTADO")
    void claveDistintaEsRechazado() {
        when(appSettingsService.returnCurrent()).thenReturn(ajustes(true));
        FidesApiService.FidesResponse ok =
                FidesApiService.FidesResponse.ok(200, "{\"estado\":\"ACEPTADO\"}");
        ok.filedAccessKey = "50602102500003101123456001000000019999999999";
        when(fidesApiService.submitToHaciendaViaFides(any())).thenReturn(ok);

        HaciendaServiceFacade.SubmitResult r = facade.submitDocument(comprobanteCompleto());

        assertThat(r.success).isFalse();
        assertThat(r.estado).isEqualTo("RECHAZADO");
        assertThat(r.errorMessage).contains("clave distinta");
    }

    @Test
    @DisplayName("Sin identificación del emisor no se llama a Fides (falla local con causa)")
    void sinEmisorNoTocaFides() {
        ConfiguracionAplicacion s = ajustes(true);
        s.setIdentificacion(null);
        when(appSettingsService.returnCurrent()).thenReturn(s);

        HaciendaServiceFacade.SubmitResult r = facade.submitDocument(comprobanteCompleto());

        assertThat(r.success).isFalse();
        assertThat(r.estado).isEqualTo("ERROR");
        assertThat(r.errorMessage).contains("emisor");
        verifyNoInteractions(fidesApiService);
    }

    @Test
    @DisplayName("Clave radicada igual a la enviada se acepta")
    void claveCoincidenteEsAceptado() {
        when(appSettingsService.returnCurrent()).thenReturn(ajustes(true));
        FidesApiService.FidesResponse ok =
                FidesApiService.FidesResponse.ok(200, "{\"estado\":\"ACEPTADO\"}");
        ok.filedAccessKey = "50602102500003101123456001000000010000000011";
        when(fidesApiService.submitToHaciendaViaFides(any())).thenReturn(ok);

        HaciendaServiceFacade.SubmitResult r = facade.submitDocument(comprobanteCompleto());

        assertThat(r.success).isTrue();
        assertThat(r.estado).isEqualTo("ACEPTADO");
    }
}
