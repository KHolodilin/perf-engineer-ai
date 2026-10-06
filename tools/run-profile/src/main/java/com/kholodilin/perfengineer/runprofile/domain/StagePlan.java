package com.kholodilin.perfengineer.runprofile.domain;

public record StagePlan(int stage, double requestedRps, MeasurementWindow ramp, MeasurementWindow hold) {
}
