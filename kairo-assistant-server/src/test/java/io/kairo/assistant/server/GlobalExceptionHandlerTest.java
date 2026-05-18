package io.kairo.assistant.server;

import java.util.Map;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.*;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void handlesBadRequest() {
        ResponseEntity<Map<String, String>> resp =
                handler.handleBadRequest(new IllegalArgumentException("invalid param"));

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        assertEquals("invalid param", resp.getBody().get("error"));
    }

    @Test
    void handlesTimeout() {
        ResponseEntity<Map<String, String>> resp =
                handler.handleTimeout(new TimeoutException("timed out"));

        assertEquals(HttpStatus.GATEWAY_TIMEOUT, resp.getStatusCode());
        assertTrue(resp.getBody().get("error").contains("timed out"));
    }

    @Test
    void handlesRateLimitFromModel() {
        ResponseEntity<Map<String, String>> resp =
                handler.handleGeneral(new RuntimeException("rate_limit_exceeded"));

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, resp.getStatusCode());
        assertTrue(resp.getBody().get("error").contains("rate limit"));
    }

    @Test
    void handlesAuthenticationError() {
        ResponseEntity<Map<String, String>> resp =
                handler.handleGeneral(new RuntimeException("401 Unauthorized"));

        assertEquals(HttpStatus.BAD_GATEWAY, resp.getStatusCode());
        assertTrue(resp.getBody().get("error").contains("authentication"));
    }

    @Test
    void handlesGenericException() {
        ResponseEntity<Map<String, String>> resp =
                handler.handleGeneral(new RuntimeException("something broke"));

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, resp.getStatusCode());
        assertTrue(resp.getBody().get("error").contains("something broke"));
    }

    @Test
    void handlesNullMessageException() {
        ResponseEntity<Map<String, String>> resp =
                handler.handleGeneral(new RuntimeException((String) null));

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, resp.getStatusCode());
        assertTrue(resp.getBody().get("error").contains("Unknown"));
    }

    @Test
    void badRequestResponseHasErrorKey() {
        ResponseEntity<Map<String, String>> resp =
                handler.handleBadRequest(new IllegalArgumentException("test"));
        assertNotNull(resp.getBody());
        assertTrue(resp.getBody().containsKey("error"));
    }

    @Test
    void timeoutResponseContainsModelOverloaded() {
        ResponseEntity<Map<String, String>> resp =
                handler.handleTimeout(new java.util.concurrent.TimeoutException("slow"));
        assertTrue(resp.getBody().get("error").contains("overloaded"));
    }

    @Test
    void classAnnotatedWithRestControllerAdvice() {
        assertTrue(GlobalExceptionHandler.class.isAnnotationPresent(
                org.springframework.web.bind.annotation.RestControllerAdvice.class));
    }
}
