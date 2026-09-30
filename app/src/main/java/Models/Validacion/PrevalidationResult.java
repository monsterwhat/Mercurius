package Models.Validacion;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class PrevalidationResult {
    
    /**
     * Starts true and only ever flips to false: {@link #addError} clears it for
     * ERROR severity. (Nothing reads this yet — consumers use
     * {@code !hasErrors()} — but a validity flag that defaults to false is a
     * trap for the first reader.)
     */
    private boolean isValid = true;
    private List<ValidationError> errors = new ArrayList<>();
    private List<ValidationError> warnings = new ArrayList<>();
    private LocalDateTime validatedAt = LocalDateTime.now();
    private String comprobanteId;
    private String numeroConsecutivo;
    
    public void addError(ValidationError error) {
        errors.add(error);
        if (error.getSeverity() == ValidationError.Severity.ERROR) {
            isValid = false;
        }
    }
    
    public void addWarning(ValidationError warning) {
        warnings.add(warning);
    }
    
    public void addValidationError(ValidationError error) {
        if (error.getSeverity() == ValidationError.Severity.ERROR) {
            addError(error);
        } else {
            addWarning(error);
        }
    }
    
    public boolean hasErrors() {
        return errors.stream().anyMatch(e -> e.getSeverity() == ValidationError.Severity.ERROR);
    }
    
    public boolean hasWarnings() {
        return !warnings.isEmpty();
    }
    
    public int getErrorCount() {
        return (int) errors.stream().filter(e -> e.getSeverity() == ValidationError.Severity.ERROR).count();
    }
    
    public int getWarningCount() {
        return warnings.size();
    }
    
    public List<ValidationError> getAllIssues() {
        List<ValidationError> all = new ArrayList<>(errors);
        all.addAll(warnings);
        return all;
    }
}