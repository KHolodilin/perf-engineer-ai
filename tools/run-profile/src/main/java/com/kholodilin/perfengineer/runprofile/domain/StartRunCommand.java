package com.kholodilin.perfengineer.runprofile.domain;

import java.util.List;

public record StartRunCommand(
        String workloadId,
        String baseUrl,
        double rps1,
        double rps2,
        double rps3,
        double rps4,
        int stageDurationSeconds,
        int rampSeconds,
        String prometheusUrl,
        List<TelemetryTarget> targets) {

    public StartRunCommand {
        targets = targets == null ? List.of() : List.copyOf(targets);
    }

    public LoadProfile profile() {
        return new LoadProfile(rps1, rps2, rps3, rps4, stageDurationSeconds, rampSeconds);
    }

    public Target target() {
        return new Target(baseUrl);
    }

    public String targetId() {
        return targets.isEmpty() ? "" : targets.getFirst().id();
    }
}
