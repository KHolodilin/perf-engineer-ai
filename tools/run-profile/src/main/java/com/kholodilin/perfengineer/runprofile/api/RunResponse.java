package com.kholodilin.perfengineer.runprofile.api;

import java.time.Instant;
import java.util.List;

public record RunResponse(
        String runId,
        String status,
        String evidenceStatus,
        Instant createdAt,
        Instant startedAt,
        Instant simulationStartedAt,
        Instant finishedAt,
        AssertionsResponse gatlingAssertions,
        GatlingResponse gatling,
        List<StageResponse> stages,
        String partialReason,
        FailureResponse failure) {

    public record AssertionsResponse(String status, double maxFailedPercent, int p95Ms) {
    }

    public record GatlingResponse(long requests, long ok, long ko) {
    }

    public record StageResponse(int stage, double requestedRps, List<WindowResponse> windows) {
    }

    public record WindowResponse(String type, Instant actualStart, Instant actualEnd, List<MetricResponse> metrics) {
    }

    public record MetricResponse(
            String name,
            Double value,
            String unit,
            String source,
            String series,
            String selector,
            String semantics,
            String method,
            boolean approximate,
            String availability,
            String reason,
            String temporalScope,
            Boolean windowScopedOrigin) {
    }

    public record FailureResponse(String code, String message) {
    }
}
