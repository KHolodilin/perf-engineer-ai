package com.kholodilin.perfengineer.runprofile.infrastructure.prometheus;

public class PrometheusUnavailableException extends RuntimeException {

    public PrometheusUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }

    public PrometheusUnavailableException(String message) {
        super(message);
    }
}
