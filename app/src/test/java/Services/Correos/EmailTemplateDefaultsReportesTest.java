package Services.Correos;

import static org.assertj.core.api.Assertions.assertThat;

import Models.Correos.EmailTemplate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Contrato de las plantillas de reportes: nombres y asuntos únicos, tipo
 * REPORTES, estilos en línea (sin recursos externos) y los marcadores que el
 * servicio de correo reemplaza al enviar.
 */
class EmailTemplateDefaultsReportesTest {

    private static final List<String> NOMBRES_ESPERADOS = List.of(
            "Reporte de ventas",
            "Reporte de inventario y stock bajo",
            "Reporte de cuentas por cobrar",
            "Reporte de cierre de caja",
            "Reporte de clientes y lealtad",
            "Reporte de margen y rentabilidad",
            "Reporte de compras y proveedores",
            "Reporte de impuestos y Hacienda",
            "Resumen ejecutivo",
            "Reporte programado (detallado)");

    /** Nombres de las cuatro plantillas predeterminadas que ya existen. */
    private static final List<String> DEFAULTS_EXISTENTES = List.of(
            "Factura electrónica",
            "Alerta de stock",
            "Reporte programado",
            "Notificación general");

    private static final List<String> PLACEHOLDERS = List.of(
            "{{reporte}}",
            "{{periodo}}",
            "{{fecha}}",
            "{{enlace}}",
            "{{negocio}}",
            "{{resumen}}",
            "{{total}}",
            "{{cliente}}");

    /** Etiqueta propia de la tarjeta de cada reporte, para que no sean clones. */
    private static final Map<String, String> ETIQUETA_DE_TARJETA = Map.ofEntries(
            Map.entry("Reporte de ventas", "Total vendido:"),
            Map.entry("Reporte de inventario y stock bajo", "Artículos por reponer:"),
            Map.entry("Reporte de cuentas por cobrar", "Saldo vencido:"),
            Map.entry("Reporte de cierre de caja", "Diferencia de caja:"),
            Map.entry("Reporte de clientes y lealtad", "Puntos otorgados:"),
            Map.entry("Reporte de margen y rentabilidad", "Margen bruto:"),
            Map.entry("Reporte de compras y proveedores", "Total comprado:"),
            Map.entry("Reporte de impuestos y Hacienda", "Impuesto del período:"),
            Map.entry("Resumen ejecutivo", "Resultado del período:"),
            Map.entry("Reporte programado (detallado)", "Total del reporte:"));

    @Test
    void devuelveDiezPlantillasDeTipoReportes() {
        List<EmailTemplate> plantillas = EmailTemplateDefaultsReportes.templates(null);

        assertThat(plantillas).hasSize(10);
        assertThat(plantillas).extracting(EmailTemplate::getNombre)
                .containsExactlyElementsOf(NOMBRES_ESPERADOS);
        assertThat(plantillas).allSatisfy(plantilla -> {
            assertThat(plantilla.getTipo()).isEqualTo("REPORTES");
            assertThat(plantilla.isStatus()).isTrue();
            assertThat(plantilla.getAsunto()).isNotBlank();
            assertThat(plantilla.getCuerpoHtml()).isNotBlank();
        });
    }

    @Test
    void nombresYAsuntosSonUnicos() {
        List<EmailTemplate> plantillas = EmailTemplateDefaultsReportes.templates(null);

        Set<String> nombres = plantillas.stream()
                .map(EmailTemplate::getNombre)
                .collect(Collectors.toSet());
        Set<String> asuntos = plantillas.stream()
                .map(EmailTemplate::getAsunto)
                .collect(Collectors.toSet());

        assertThat(nombres).hasSameSizeAs(plantillas);
        assertThat(asuntos).hasSameSizeAs(plantillas);
        assertThat(plantillas).allSatisfy(plantilla ->
                assertThat(plantilla.getAsunto()).contains("{{periodo}}"));
    }

    @Test
    void cadaCuerpoNombraSuReporteYTraeLosMarcadores() {
        for (EmailTemplate plantilla : EmailTemplateDefaultsReportes.templates(null)) {
            assertThat(plantilla.getCuerpoHtml())
                    .as("el cuerpo de %s nombra su reporte", plantilla.getNombre())
                    .contains(plantilla.getNombre());

            assertThat(plantilla.getCuerpoHtml())
                    .as("marcadores de %s", plantilla.getNombre())
                    .contains(PLACEHOLDERS.toArray(new String[0]));

            assertThat(plantilla.getCuerpoHtml())
                    .as("tarjeta y CTA de %s", plantilla.getNombre())
                    .contains("role=\"presentation\"")
                    .contains("#f8fafc")
                    .contains("#e5e9f2")
                    .contains("border-radius:10px")
                    .contains("background:#0b3b8c")
                    .contains("padding:12px 22px")
                    .contains(ETIQUETA_DE_TARJETA.get(plantilla.getNombre()));
        }
    }

    @Test
    void losCuerposNoUsanHojasDeEstiloNiScriptsExternos() {
        for (EmailTemplate plantilla : EmailTemplateDefaultsReportes.templates(null)) {
            assertThat(plantilla.getCuerpoHtml())
                    .as("recursos externos de %s", plantilla.getNombre())
                    .doesNotContainIgnoringCase("<style", "<link", "<script");
        }
    }

    @Test
    void losNombresNuevosNoChocanConLosCuatroPredeterminados() {
        List<String> nombres = EmailTemplateDefaultsReportes.templates(null).stream()
                .map(EmailTemplate::getNombre)
                .toList();

        assertThat(nombres).doesNotContainAnyElementsOf(DEFAULTS_EXISTENTES);
        assertThat(nombres).contains("Reporte programado (detallado)");
        assertThat(nombres).doesNotContain("Reporte programado");
    }
}
