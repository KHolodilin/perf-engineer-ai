package com.kholodilin.perfengineer.runprofile.infrastructure.prometheus;

import java.time.Duration;

public final class MetricMeasurabilityPolicy {

    public boolean accepts(Duration windowDuration, Duration rangeVector, Duration scrapeInterval) {
        if (windowDuration.compareTo(scrapeInterval.multipliedBy(2)) < 0) {
            return false;
        }
        return rangeVector.compareTo(windowDuration) <= 0;
    }
}
