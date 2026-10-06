package com.kholodilin.perfengineer.runprofile.domain;

public record GatlingAssertions(AssertionStatus status, double maxFailedPercent, int p95Ms) {
}
