package org.dar316.spring_ai.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * Handles exceptions explicitly thrown with HTTP status codes (e.g., GitHub 404/422).
     */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ErrorResponse> handleResponseStatusException(ResponseStatusException ex) {
        log.warn("Handled ResponseStatusException: Status {}, Reason: {}", ex.getStatusCode(), ex.getReason());
        ErrorResponse body = new ErrorResponse(ex.getReason(), ex.getClass().getSimpleName());
        return ResponseEntity.status(ex.getStatusCode()).body(body);
    }

    /**
     * Handles bad input or invalid arguments (e.g., blank query, invalid tech version).
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgumentException(IllegalArgumentException ex) {
        log.warn("Handled IllegalArgumentException: {}", ex.getMessage());
        ErrorResponse body = new ErrorResponse(ex.getMessage(), "Invalid input");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    /**
     * Handles invalid state errors (e.g., reranking API failure, Qdrant misconfiguration).
     */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ErrorResponse> handleIllegalStateException(IllegalStateException ex) {
        log.error("Handled IllegalStateException: {}", ex.getMessage(), ex);
        ErrorResponse body = new ErrorResponse(ex.getMessage(), "Internal server error");
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }

    /**
     * Catch-all for unexpected errors (e.g., Qdrant connection refused, NullPointerException).
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGenericException(Exception ex) {
        log.error("Unhandled exception caught by global handler", ex);
        ErrorResponse body = new ErrorResponse("An unexpected internal error occurred", "Internal server error");
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }

    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<Map<String, String>> handleMissingServletRequestPart(
            MissingServletRequestPartException ex
    ) {
        return ResponseEntity.badRequest()
                .body(Map.of(
                        "message", "Required multipart part '%s' is missing."
                                .formatted(ex.getRequestPartName()),
                        "error", "Bad request"
                ));
    }

    /**
     * Standard JSON error payload.
     * The "message" field is explicitly parsed by chat.py's _rag_http_error_message().
     */
    public record ErrorResponse(String message, String error) {}
}
