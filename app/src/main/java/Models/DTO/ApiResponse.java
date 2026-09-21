package Models.DTO;

import java.util.Collections;
import java.util.List;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

/**
 * Standard API error response envelope.
 * All public REST API endpoints return errors in this format.
 *
 * @param <T> the type of the success payload
 */
public class ApiResponse<T> {

    @Nullable
    private T data;

    @Nullable
    private ErrorInfo error;

    public ApiResponse() {
    }

    public ApiResponse(@Nullable T data, @Nullable ErrorInfo error) {
        this.data = data;
        this.error = error;
    }

    /**
     * Create a success response wrapping the given data.
     */
    @Nonnull
    public static <T> ApiResponse<T> ok(@Nonnull T data) {
        return new ApiResponse<>(data, null);
    }

    /**
     * Create an error response with a code and message.
     */
    @Nonnull
    public static <T> ApiResponse<T> error(@Nonnull String code, @Nonnull String message) {
        return new ApiResponse<>(null, new ErrorInfo(code, message, Collections.emptyList()));
    }

    /**
     * Create an error response with a code, message, and field-level details.
     */
    @Nonnull
    public static <T> ApiResponse<T> error(@Nonnull String code, @Nonnull String message,
                                            @Nonnull List<String> details) {
        return new ApiResponse<>(null, new ErrorInfo(code, message, details));
    }

    @Nullable
    public T getData() {
        return data;
    }

    public void setData(@Nullable T data) {
        this.data = data;
    }

    @Nullable
    public ErrorInfo getError() {
        return error;
    }

    public void setError(@Nullable ErrorInfo error) {
        this.error = error;
    }

    /**
     * Error details returned in an {@link ApiResponse}.
     */
    public static class ErrorInfo {

        @Nonnull
        private String code; // e.g. "VALIDATION_ERROR", "NOT_FOUND", "UNAUTHORIZED"

        @Nonnull
        private String message; // Human-readable message

        @Nonnull
        private List<String> details; // Optional field-level errors

        public ErrorInfo() {
        }

        public ErrorInfo(@Nonnull String code, @Nonnull String message,
                         @Nonnull List<String> details) {
            this.code = code;
            this.message = message;
            this.details = details;
        }

        @Nonnull
        public String getCode() {
            return code;
        }

        public void setCode(@Nonnull String code) {
            this.code = code;
        }

        @Nonnull
        public String getMessage() {
            return message;
        }

        public void setMessage(@Nonnull String message) {
            this.message = message;
        }

        @Nonnull
        public List<String> getDetails() {
            return details;
        }

        public void setDetails(@Nonnull List<String> details) {
            this.details = details;
        }
    }
}
