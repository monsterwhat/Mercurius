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

    @Test
    void wrapHtmlInsertaElCuerpoSinEscaparloPeroEscapaElTitulo() {
        String html = EmailLayout.wrapHtml("Aviso <001>",
                "<h2>Su factura</h2><p>Total <strong>1000</strong></p>", "Mi Negocio");

        assertThat(html).contains("<!doctype html>");
        assertThat(html).contains("<h2>Su factura</h2>");
        assertThat(html).contains("<strong>1000</strong>");
        // El título sí se escapa.
        assertThat(html).contains("Aviso &lt;001&gt;");
    }

    @Test
    void elPieEnlazaElRepositorio() {
        String html = EmailLayout.wrap("Asunto", "Cuerpo", "Mi Negocio");
        assertThat(html).contains("https://github.com/monsterwhat/Mercurius");
    }
}
