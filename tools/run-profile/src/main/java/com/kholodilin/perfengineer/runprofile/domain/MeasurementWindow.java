package com.kholodilin.perfengineer.runprofile.domain;

import java.time.Duration;
import java.time.Instant;

public record MeasurementWindow(WindowType type, Instant actualStart, Instant actualEnd) {

    public Duration duration() {
        return Duration.between(actualStart, actualEnd);
    }

    public boolean contains(Instant timestamp) {
        return timestamp.isAfter(actualStart) && !timestamp.isAfter(actualEnd);
    }
}
