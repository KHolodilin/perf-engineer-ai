package com.kholodilin.perfengineer.runprofile.api;

import java.util.List;

public record StartRunRequest(
        WorkloadRequest workload,
        TargetRequest target,
        ProfileRequest profile,
        TelemetryRequest telemetry) {

    public record WorkloadRequest(String id) {
    }

    public record TargetRequest(String baseUrl) {
    }

    public record ProfileRequest(
            Double rps1,
            Double rps2,
            Double rps3,
            Double rps4,
            Integer stageDurationSeconds,
            Integer rampSeconds) {
    }

    public record TelemetryRequest(String prometheusUrl, List<TargetSpec> targets) {
    }

    public record TargetSpec(String id, String type, String job) {
    }
}
