package com.kholodilin.perfengineer.runprofile.application;

import java.util.List;

import com.kholodilin.perfengineer.runprofile.domain.MetricEvidence;

public record TelemetryEvidence(List<MetricEvidence> metrics, boolean prometheusAvailable) {

    public TelemetryEvidence {
        metrics = metrics == null ? List.of() : List.copyOf(metrics);
    }
}
