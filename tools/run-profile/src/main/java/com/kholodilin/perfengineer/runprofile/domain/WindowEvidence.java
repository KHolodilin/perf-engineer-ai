package com.kholodilin.perfengineer.runprofile.domain;

import java.time.Instant;
import java.util.List;

public record WindowEvidence(
        WindowType type,
        Instant actualStart,
        Instant actualEnd,
        List<MetricEvidence> metrics) {

    public WindowEvidence {
        metrics = metrics == null ? List.of() : List.copyOf(metrics);
    }
}
