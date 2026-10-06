package com.kholodilin.perfengineer.runprofile.domain;

public enum AvailabilityReason {
    PROMETHEUS_UNAVAILABLE,
    SIMULATION_START_UNAVAILABLE,
    INSUFFICIENT_SAMPLES,
    SERIES_NOT_FOUND,
    SAMPLE_OUTSIDE_WINDOW,
    NOT_A_NUMBER
}
