package com.kholodilin.perfengineer.runprofile.application;

import java.time.Duration;

import com.kholodilin.perfengineer.runprofile.config.RunProfileProperties;

public final class RetentionPolicy {

    private RetentionPolicy() {
    }

    public static void check(RunProfileProperties properties) {
        if (properties.getQueueCapacity() < 1) {
            throw new IllegalStateException("queueCapacity must be a positive value");
        }
        Duration executionFloor = properties.getMaxProfileDuration()
                .plus(properties.getStartupAllowance())
                .plus(properties.getCollectionAllowance());
        if (properties.getExecutionTimeout().compareTo(executionFloor) < 0) {
            throw new IllegalStateException("executionTimeout must cover the profile, startup, and collection allowances");
        }
        Duration retryFloor = properties.getExecutionTimeout().plus(properties.getClientRetryWindow());
        if (properties.getRunRetention().compareTo(retryFloor) <= 0) {
            throw new IllegalStateException("runRetention must be longer than executionTimeout plus clientRetryWindow");
        }
        if (properties.getIdempotencyRetention().compareTo(retryFloor) <= 0) {
            throw new IllegalStateException("idempotencyRetention must be longer than executionTimeout plus clientRetryWindow");
        }
        if (properties.getIdempotencyRetention().compareTo(properties.getRunRetention()) > 0) {
            throw new IllegalStateException("idempotencyRetention must not exceed runRetention");
        }
        if (properties.getEffectiveScrapeInterval().isZero() || properties.getEffectiveScrapeInterval().isNegative()) {
            throw new IllegalStateException("effectiveScrapeInterval must be positive");
        }
    }
}
