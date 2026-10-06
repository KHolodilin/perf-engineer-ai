package com.kholodilin.perfengineer.runprofile.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public final class WindowPlanner {

    public List<StagePlan> plan(Instant simulationStartedAt, LoadProfile profile) {
        Instant cursor = simulationStartedAt;
        List<StagePlan> stages = new ArrayList<>(4);
        for (int stage = 1; stage <= 4; stage++) {
            Instant rampEnd = cursor.plusSeconds(profile.rampSeconds());
            Instant holdEnd = rampEnd.plusSeconds(profile.stageDurationSeconds());
            stages.add(new StagePlan(
                    stage,
                    profile.requestedRps(stage),
                    new MeasurementWindow(WindowType.RAMP, cursor, rampEnd),
                    new MeasurementWindow(WindowType.HOLD, rampEnd, holdEnd)));
            cursor = holdEnd;
        }
        return List.copyOf(stages);
    }
}
