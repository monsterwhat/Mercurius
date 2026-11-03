/**
 * Campos de fecha en formato dd/mm/aaaa.
 *
 * Chromium pinta <input type="date"> con el formato del NAVEGADOR (mm/dd/yyyy en
 * un Chrome en ingles), no con el idioma de la pagina. Eso es lo que hacia que
 * los filtros de reportes mostraran 10/04/2026 en vez de 04/10/2026.
 *
 * El input nativo se conserva (es el que envia el valor ISO y el que lee el JS
 * existente); encima se muestra un campo de texto dd/mm/aaaa que lo sincroniza.
 * La validacion (required/pattern) vive en el campo visible, que es el que el
 * navegador puede enfocar si falta o esta mal escrito.
 */

const FORMATO_DD_MM_AAAA = /^(\d{2})\/(\d{2})\/(\d{4})$/;

function isoADisplay(iso) {
    if (!iso) {
        return '';
    }
    const partes = iso.split('-');
    if (partes.length !== 3) {
        return '';
    }
    return partes[2] + '/' + partes[1] + '/' + partes[0];
}

function displayAIso(texto) {
    const coincidencia = FORMATO_DD_MM_AAAA.exec((texto || '').trim());
    if (!coincidencia) {
        return null;
    }
    const dia = Number(coincidencia[1]);
    const mes = Number(coincidencia[2]);
    const anio = Number(coincidencia[3]);
    const fecha = new Date(anio, mes - 1, dia);
    if (fecha.getFullYear() !== anio || fecha.getMonth() !== mes - 1 || fecha.getDate() !== dia) {
        return null;
    }
    return coincidencia[3] + '-' + coincidencia[2] + '-' + coincidencia[1];
}

function mejorarFecha(nativo) {
    if (!nativo || nativo.dataset.fechaEs === '1' || nativo.type !== 'date') {
        return;
    }
    nativo.dataset.fechaEs = '1';

    const visible = document.createElement('input');
    visible.type = 'text';
    visible.className = nativo.className;
    visible.placeholder = 'dd/mm/aaaa';
    visible.maxLength = 10;
    visible.inputMode = 'numeric';
    visible.autocomplete = 'off';
    visible.pattern = '\\d{2}/\\d{2}/\\d{4}';
    visible.title = 'Fecha en formato dd/mm/aaaa';
    visible.dataset.fechaVisible = '1';
    if (nativo.id) {
        visible.id = nativo.id + '-visible';
    }
    if (nativo.required) {
        visible.required = true;
        // La validacion nativa del campo oculto no puede enfocarlo; se mueve al
        // campo visible para que el navegador muestre el mensaje correctamente.
        nativo.removeAttribute('required');
    }
    const descrito = nativo.getAttribute('aria-describedby');
    if (descrito) {
        visible.setAttribute('aria-describedby', descrito);
    }
    // Se oculta sin display:none: el input nativo sigue enviando su valor ISO.
    nativo.style.position = 'absolute';
    nativo.style.width = '1px';
    nativo.style.height = '1px';
    nativo.style.opacity = '0';
    nativo.style.pointerEvents = 'none';
    nativo.insertAdjacentElement('afterend', visible);

    if (nativo.id) {
        document.querySelectorAll('label[for="' + nativo.id + '"]')
            .forEach(function (label) { label.htmlFor = visible.id; });
    }

    function sincronizarVisible() {
        visible.value = isoADisplay(nativo.value);
    }

    function aplicarTexto() {
        if (!visible.value.trim()) {
            visible.setCustomValidity('');
            if (nativo.value) {
                nativo.value = '';
                nativo.dispatchEvent(new Event('change', { bubbles: true }));
            }
            return;
        }
        const iso = displayAIso(visible.value);
        if (!iso) {
            visible.setCustomValidity('Escriba una fecha válida en formato dd/mm/aaaa');
            return;
        }
        visible.setCustomValidity('');
        if (nativo.value !== iso) {
            nativo.value = iso;
            nativo.dispatchEvent(new Event('input', { bubbles: true }));
            nativo.dispatchEvent(new Event('change', { bubbles: true }));
        }
    }

    visible.addEventListener('input', aplicarTexto);
    visible.addEventListener('blur', function () {
        const iso = displayAIso(visible.value);
        visible.value = iso ? isoADisplay(iso) : visible.value.trim();
        sincronizarVisible();
    });
    nativo.addEventListener('change', sincronizarVisible);
    sincronizarVisible();
}

function mejorarFechas(raiz) {
    if (!raiz || !raiz.querySelectorAll) {
        return;
    }
    raiz.querySelectorAll('input[type="date"]').forEach(mejorarFecha);
}

export function installDateFields() {
    mejorarFechas(document);
    document.body.addEventListener('htmx:afterSwap', function (evento) {
        mejorarFechas(evento.target);
    });
    document.body.addEventListener('htmx:afterSettle', function (evento) {
        mejorarFechas(evento.target);
    });
}

if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', installDateFields);
} else {
    installDateFields();
}
