package com.kholodilin.perfengineer.runprofile.infrastructure.prometheus;

import java.util.List;

public record VectorResult(List<VectorSample> samples) {

    public VectorResult {
        samples = samples == null ? List.of() : List.copyOf(samples);
    }

    public static VectorResult empty() {
        return new VectorResult(List.of());
    }
}
