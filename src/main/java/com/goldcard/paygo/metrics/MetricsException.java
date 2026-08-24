package com.goldcard.paygo.metrics;

public class MetricsException extends RuntimeException {
    public MetricsException(String message) { super(message); }
    public MetricsException(String message, Throwable cause) { super(message, cause); }
}
