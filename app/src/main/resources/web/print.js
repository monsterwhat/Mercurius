/**
 * Ported verbatim from the legacy page scripts:
 *   META-INF/resources/resources/js/functions.js  (printPDF)
 *
 * Behavior kept identical: XHR GET (blob) -> FileReader.readAsBinaryString ->
 * base64 via btoa -> new window with a full-size iframe embedding the PDF ->
 * focus + print once loaded.
 */

export function printPDF(url) {
    var xhr = new XMLHttpRequest();
    xhr.open('GET', url, true);
    xhr.responseType = 'blob';
    xhr.onload = function () {
        if (xhr.status === 200) {
            var blob = xhr.response;
            var fileReader = new FileReader();
            fileReader.onload = function () {
                var data = this.result;
                var printWindow = window.open('', '_blank');
                printWindow.document.write('<iframe width="100%" height="100%" src="data:application/pdf;base64,' + btoa(data) + '"></iframe>');
                printWindow.document.close();
                printWindow.onload = function () {
                    printWindow.focus();
                    printWindow.print();
                };
            };
            fileReader.readAsBinaryString(blob);
        }
    };
    xhr.send();
}

// Legacy parity: functions.js exposed printPDF as a window global called from
// page event handlers; registering it here also keeps it out of tree-shaking
// until modules import it explicitly.
window.printPDF = printPDF;
