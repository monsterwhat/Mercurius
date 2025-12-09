function printPDF(url) {
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
