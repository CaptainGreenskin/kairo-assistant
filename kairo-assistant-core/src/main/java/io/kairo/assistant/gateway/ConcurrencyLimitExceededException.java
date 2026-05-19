package io.kairo.assistant.gateway;

public class ConcurrencyLimitExceededException extends RuntimeException {
    public ConcurrencyLimitExceededException(String message) {
        super(message);
    }
}
