package com.kholodilin.perfengineer.runprofile.infrastructure.prometheus;

import java.time.Instant;
import java.util.Map;

public record VectorSample(Map<String, String> labels, Instant timestamp, double value) {

    public VectorSample {
        labels = labels == null ? Map.of() : Map.copyOf(labels);
    }

    public boolean nan() {
        return Double.isNaN(value);
    }
}
