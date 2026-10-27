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
