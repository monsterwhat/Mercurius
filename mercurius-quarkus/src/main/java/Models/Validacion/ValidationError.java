package Models.Validacion;

import java.time.LocalDateTime;
import lombok.Data;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ValidationError {
    
    public enum Category {
        CABYS,
        TAX_CALCULATION,
        RECEPTOR_INFO,
        INFORMACION_REFERENCIA,
        DOCUMENT_TYPE,
        RESUMEN_STRUCTURE,
        HEADER_INFO,
        LINE_DETAIL
    }
    
    public enum Severity {
        ERROR,
        WARNING
    }
    
    private Category category;
    private String field;
    private String code;
    private String message;
    private Object expected;
    private Object actual;
    private Severity severity = Severity.ERROR;
    private LocalDateTime timestamp = LocalDateTime.now();
    
    public ValidationError(Category category, String field, String code, String message) {
        this.category = category;
        this.field = field;
        this.code = code;
        this.message = message;
        this.timestamp = LocalDateTime.now();
    }
    
    public ValidationError(Category category, String field, String code, String message, Object expected, Object actual) {
        this(category, field, code, message);
        this.expected = expected;
        this.actual = actual;
    }
    
    public ValidationError(Category category, String field, String code, String message, Severity severity) {
        this(category, field, code, message);
        this.severity = severity;
    }
}