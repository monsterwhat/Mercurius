/**
 * Ported from the legacy page scripts:
 *   META-INF/resources/resources/js/functions.js  (printPDF)
 *
 * Behavior kept identical: XHR GET (blob) -> FileReader.readAsBinaryString ->
 * base64 via btoa -> new window with a full-size iframe embedding the PDF ->
 * focus + print once loaded.
 *
 * WHAT CHANGED
 * ------------
 * Every failure path used to be silent: a non-200 status, a network error, a
 * FileReader failure or a blocked popup did nothing at all, so the operator
 * pressed "Imprimir" and nothing happened. Each branch now reports both to the
 * console and to the shared toast (`window.mercuriusNotify`, defined in
 * web/index.js), and a null popup is checked BEFORE `document.write` is called
 * on it (the old order threw a TypeError that was never surfaced).
 */

function reportPrintFailure(message, error) {
    if (error) {
        console.error('[mercurius] printPDF:', message, error);
    } else {
        console.error('[mercurius] printPDF:', message);
    }
    if (typeof window.mercuriusNotify === 'function') {
        window.mercuriusNotify(message, 'error');
    }
}

export function printPDF(url) {
    var xhr = new XMLHttpRequest();
    xhr.open('GET', url, true);
    xhr.responseType = 'blob';

    xhr.onload = function () {
        if (xhr.status !== 200) {
            reportPrintFailure('No se pudo generar el PDF (error ' + xhr.status + ').');
            return;
        }
        var blob = xhr.response;
        if (!blob) {
            reportPrintFailure('El servidor no devolvió el PDF.');
            return;
        }
        var fileReader = new FileReader();
        fileReader.onload = function () {
            var data = this.result;
            var payload;
            try {
                payload = btoa(data);
            } catch (error) {
                reportPrintFailure('No se pudo preparar el PDF para imprimir.', error);
                return;
            }
            var printWindow = window.open('', '_blank');
            if (!printWindow) {
                reportPrintFailure('El navegador bloqueó la ventana de impresión. Permita las ventanas emergentes para imprimir.');
                return;
            }
            printWindow.document.write('<iframe width="100%" height="100%" src="data:application/pdf;base64,' + payload + '"></iframe>');
            printWindow.document.close();
            printWindow.onload = function () {
                printWindow.focus();
                printWindow.print();
            };
        };
        fileReader.onerror = function () {
            reportPrintFailure('No se pudo leer el PDF para imprimir.', fileReader.error);
        };
        fileReader.readAsBinaryString(blob);
    };

    xhr.onerror = function () {
        reportPrintFailure('No hay conexión con el servidor para generar el PDF.');
    };
    xhr.onabort = function () {
        reportPrintFailure('La impresión se canceló antes de recibir el PDF.');
    };

    xhr.send();
}

// Legacy parity: functions.js exposed printPDF as a window global called from
// page event handlers; registering it here also keeps it out of tree-shaking
// until modules import it explicitly.
window.printPDF = printPDF;

/**
 * Imprime un tiquete HTML (no PDF) en un iframe oculto.
 *
 * El servidor sirve el documento con un `@page` de alto estimado; aquí se mide
 * la altura real del contenido y se reescribe el `@page` antes de imprimir.
 * Esto evita la página en blanco que deja un alto fijo demasiado grande y la
 * segunda página que produce uno demasiado corto.
 *
 * @param {string} url           HTML del tiquete (GET /api/app/recibos/{id}/tiquete)
 * @param {object} [options]
 * @param {string} [options.fallbackPdfUrl] PDF a imprimir si el HTML falla
 * @param {number} [options.anchoMm=72]     ancho útil del rollo
 */
export function printHTML(url, options) {
    options = options || {};
    var anchoMm = options.anchoMm || 72;
    var fallbackPdfUrl = options.fallbackPdfUrl || null;

    var frame = document.createElement('iframe');
    frame.setAttribute('aria-hidden', 'true');
    frame.setAttribute('tabindex', '-1');
    frame.style.position = 'fixed';
    frame.style.left = '-10000px';
    frame.style.top = '0';
    frame.style.width = anchoMm + 'mm';
    frame.style.height = '200mm';
    frame.style.border = '0';

    var terminado = false;
    var timeoutId = null;

    function limpiar() {
        if (terminado) {
            return;
        }
        terminado = true;
        if (timeoutId) {
            clearTimeout(timeoutId);
            timeoutId = null;
        }
        if (frame.parentNode) {
            frame.parentNode.removeChild(frame);
        }
    }

    function fallar(mensaje, error) {
        limpiar();
        if (fallbackPdfUrl && typeof window.printPDF === 'function') {
            console.warn('[mercurius] printHTML:', mensaje, '→ fallback PDF');
            window.printPDF(fallbackPdfUrl);
            return;
        }
        reportPrintFailure(mensaje, error);
    }

    frame.onload = function () {
        try {
            var doc = frame.contentDocument;
            if (!doc) {
                fallar('No se pudo acceder al tiquete para imprimir.');
                return;
            }
            var imprimir = function () {
                try {
                    var tiquete = doc.getElementById('tiquete');
                    var pageSize = doc.getElementById('page-size');
                    if (tiquete && pageSize) {
                        // getBoundingClientRect mide en px CSS (96 dpi) porque el
                        // iframe tiene el ancho real del rollo.
                        var altoPx = tiquete.getBoundingClientRect().height;
                        var altoMm = Math.ceil(altoPx * 25.4 / 96) + 2;
                        pageSize.textContent =
                            '@page { size: ' + anchoMm + 'mm ' + altoMm + 'mm; margin: 0; }';
                    }
                    window.requestAnimationFrame(function () {
                        try {
                            frame.contentWindow.focus();
                            frame.contentWindow.print();
                            // afterprint no es fiable en todos los navegadores;
                            // se limpia también por temporizador.
                            try {
                                frame.contentWindow.addEventListener('afterprint', limpiar);
                            } catch (e) {
                                console.debug('[mercurius] printHTML: afterprint no disponible', e);
                            }
                        } catch (e) {
                            fallar('El navegador no permitió imprimir el tiquete.', e);
                        }
                    });
                } catch (e) {
                    fallar('No se pudo preparar el tiquete para imprimir.', e);
                }
            };
            if (doc.fonts && doc.fonts.ready && typeof doc.fonts.ready.then === 'function') {
                doc.fonts.ready.then(imprimir).catch(imprimir);
            } else {
                imprimir();
            }
        } catch (e) {
            fallar('No se pudo preparar el tiquete para imprimir.', e);
        }
    };

    frame.onerror = function () {
        fallar('No se pudo cargar el tiquete para imprimir.');
    };

    timeoutId = setTimeout(function () {
        fallar('La impresión del tiquete tardó demasiado.');
    }, 8000);

    frame.src = url;
    document.body.appendChild(frame);
}

// Mismo contrato global que printPDF: los onclick heredados y los fragments
// HTMX no importan módulos.
window.printHTML = printHTML;
