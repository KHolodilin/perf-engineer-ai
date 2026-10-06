package com.kholodilin.perfengineer.runprofile.domain;

public enum MetricSemantics {
    COUNTER_RATE,
    HISTOGRAM_QUANTILE,
    HISTOGRAM_MEAN,
    GAUGE_SAMPLE,
    MAX_OBSERVED_GAUGE,
    COUNTER_DELTA
}
