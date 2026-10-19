package com.selfheal.common.client;

/**
 * Thrown when an outgoing link call is short-circuited due to an open circuit breaker.
 */
public class CircuitBreakerOpenException extends RuntimeException {
    public CircuitBreakerOpenException(String message) {
        super(message);
    }
}
