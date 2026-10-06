package com.kholodilin.perfengineer.runprofile.domain;

public record LoadProfile(
        double rps1,
        double rps2,
        double rps3,
        double rps4,
        int stageDurationSeconds,
        int rampSeconds) {

    public long profileDurationSeconds() {
        return 4L * ((long) rampSeconds + stageDurationSeconds);
    }

    public double requestedRps(int stage) {
        return switch (stage) {
            case 1 -> rps1;
            case 2 -> rps2;
            case 3 -> rps3;
            case 4 -> rps4;
            default -> throw new IllegalArgumentException("stage must be 1..4");
        };
    }
}
