package Services.Correos;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class EmailLayoutTest {

    @Test
    void envuelveElCuerpoConMarcaYEscapaHtml() {
        String html = EmailLayout.wrap("Su factura <001>",
                "Hola <b>cliente</b>\nTotal: 1000 & 500", "Mi Negocio");

        assertThat(html).contains("<!doctype html>");
        assertThat(html).contains("Mi Negocio");
        // El título y el cuerpo se escapan: nada del remitente se interpreta.
        assertThat(html).contains("Su factura &lt;001&gt;");
        assertThat(html).contains("Hola &lt;b&gt;cliente&lt;/b&gt;");
        assertThat(html).contains("Total: 1000 &amp; 500");
        assertThat(html).doesNotContain("<b>cliente</b>");
    }

    @Test
    void usaMercuriusCuandoNoHayNombreDeNegocio() {
        assertThat(EmailLayout.wrap("Asunto", "Cuerpo", null)).contains("Mercurius");
        assertThat(EmailLayout.wrap("Asunto", "Cuerpo", "  ")).contains("Mercurius");
    }
}
