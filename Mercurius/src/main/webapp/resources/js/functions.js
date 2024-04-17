function focusNext(event, nextElementId) {
    if (event.keyCode === 13) { // Enter key
        var nextElement = document.getElementById(nextElementId);
        if (nextElement) {
            nextElement.focus();
            return false; // Prevent default behavior of the enter key
        }
    }
    return true; // Continue default behavior for other keys
}
