package io.kairo.assistant.server;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleBadRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest()
                .body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(java.util.concurrent.TimeoutException.class)
    public ResponseEntity<Map<String, String>> handleTimeout(java.util.concurrent.TimeoutException e) {
        log.warn("Request timed out: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT)
                .body(Map.of("error", "Request timed out. The model may be overloaded."));
    }

    // Spring throws this for unknown static resources (e.g. /favicon.ico when
    // no icon file is shipped). Browsers refetch the icon on every navigation,
    // so a 500 per request floods the log and the browser DevTools error pane.
    // 404 is the correct status and is silent in DevTools.
    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
    public ResponseEntity<Void> handleNoResource(
            org.springframework.web.servlet.resource.NoResourceFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleGeneral(Exception e) {
        log.error("Unhandled exception", e);

        String message = e.getMessage();
        if (message != null && message.contains("rate_limit")) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(Map.of("error", "Model API rate limit exceeded. Please retry shortly."));
        }
        if (message != null && (message.contains("401") || message.contains("authentication"))) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(Map.of("error", "Model API authentication failed. Check API key configuration."));
        }

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "Internal error: " + (message != null ? message : "Unknown")));
    }
}
