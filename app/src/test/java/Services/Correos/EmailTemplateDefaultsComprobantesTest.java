package Services.Correos;

import static org.assertj.core.api.Assertions.assertThat;

import Models.Correos.EmailTemplate;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Verifica el catálogo de plantillas de comprobantes electrónicos: cantidad,
 * unicidad, tipo, placeholders y ausencia de CSS/scripts externos.
 */
class EmailTemplateDefaultsComprobantesTest {

    /** Nombres que ya usan las plantillas predeterminadas generales. */
    private static final Set<String> NOMBRES_EXISTENTES = Set.of(
            "Factura electrónica",
            "Alerta de stock",
            "Reporte programado",
            "Notificación general");

    private static final List<String> PLACEHOLDERS_BASE = List.of(
            "{{cliente}}", "{{consecutivo}}", "{{fecha}}", "{{total}}", "{{enlace}}");

    @Test
    void devuelveNuevePlantillas() {
        assertThat(EmailTemplateDefaultsComprobantes.templates(null)).hasSize(9);
    }

    @Test
    void nombresYAsuntosSonUnicos() {
        List<EmailTemplate> templates = EmailTemplateDefaultsComprobantes.templates(null);

        Set<String> nombres = new HashSet<>();
        Set<String> asuntos = new HashSet<>();
        for (EmailTemplate template : templates) {
            assertThat(template.getNombre()).as("nombre").isNotBlank();
            assertThat(template.getAsunto()).as("asunto").isNotBlank();
            assertThat(nombres.add(template.getNombre()))
                    .as("nombre duplicado: %s", template.getNombre()).isTrue();
            assertThat(asuntos.add(template.getAsunto()))
                    .as("asunto duplicado: %s", template.getAsunto()).isTrue();
        }
    }

    @Test
    void todasSonDelTipoComprobantes() {
        for (EmailTemplate template : EmailTemplateDefaultsComprobantes.templates(null)) {
            assertThat(template.getTipo())
                    .as("tipo de %s", template.getNombre())
                    .isEqualTo("COMPROBANTES");
        }
    }

    @Test
    void cadaCuerpoTieneLosPlaceholdersBaseYNoUsaCssExterno() {
        for (EmailTemplate template : EmailTemplateDefaultsComprobantes.templates(null)) {
            String html = template.getCuerpoHtml();
            assertThat(html).as("cuerpo de %s", template.getNombre()).isNotBlank();

            for (String placeholder : PLACEHOLDERS_BASE) {
                assertThat(html)
                        .as("%s debe incluir %s", template.getNombre(), placeholder)
                        .contains(placeholder);
            }

            // El correo se renderiza con estilos en línea: nada de CSS ni scripts.
            assertThat(html.toLowerCase(Locale.ROOT))
                    .as("HTML de %s sin recursos externos", template.getNombre())
                    .doesNotContain("<style", "<link", "<script");

            // El cuerpo debe indicar qué se adjunta.
            assertThat(html).as("adjuntos de %s", template.getNombre())
                    .contains("XML")
                    .contains("PDF");
        }
    }

    @Test
    void cadaCasoIncluyeSusPlaceholdersPropios() {
        List<EmailTemplate> templates = EmailTemplateDefaultsComprobantes.templates(null);

        // Los placeholders propios, en el mismo orden en que se registran.
        List<List<String>> propios = List.of(
                List.of("{{tipoComprobante}}", "{{moneda}}"),
                List.of("{{moneda}}"),
                List.of("{{referencia}}", "{{motivo}}", "{{moneda}}"),
                List.of("{{referencia}}", "{{motivo}}"),
                List.of("{{moneda}}"),
                List.of("{{negocio}}"),
                List.of("{{saldo}}", "{{referencia}}"),
                List.of("{{estado}}", "{{motivo}}"),
                List.of("{{vencimiento}}", "{{saldo}}", "{{moneda}}"));

        for (int i = 0; i < templates.size(); i++) {
            EmailTemplate template = templates.get(i);
            for (String placeholder : propios.get(i)) {
                assertThat(template.getCuerpoHtml())
                        .as("%s debe incluir %s", template.getNombre(), placeholder)
                        .contains(placeholder);
            }
        }
    }

    @Test
    void losNombresNuevosNoChocanConLasPredeterminadasExistentes() {
        for (EmailTemplate template : EmailTemplateDefaultsComprobantes.templates(null)) {
            assertThat(NOMBRES_EXISTENTES)
                    .as("nombre en conflicto: %s", template.getNombre())
                    .doesNotContain(template.getNombre());
        }
    }
}
