package com.kholodilin.perfengineer.runprofile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import com.kholodilin.perfengineer.runprofile.application.ProfileValidator;
import com.kholodilin.perfengineer.runprofile.application.RequestRejectedException;
import com.kholodilin.perfengineer.runprofile.domain.LoadProfile;
import com.kholodilin.perfengineer.runprofile.domain.MeasurementWindow;
import com.kholodilin.perfengineer.runprofile.domain.Run;
import com.kholodilin.perfengineer.runprofile.domain.RunStatus;
import com.kholodilin.perfengineer.runprofile.domain.StagePlan;
import com.kholodilin.perfengineer.runprofile.domain.StartRunCommand;
import com.kholodilin.perfengineer.runprofile.domain.TelemetryTarget;
import com.kholodilin.perfengineer.runprofile.domain.WindowPlanner;
import com.kholodilin.perfengineer.runprofile.domain.WindowType;
import org.junit.jupiter.api.Test;

class ProfileAndWindowTest {

    private final ProfileValidator validator = new ProfileValidator(Duration.ofHours(2));

    @Test
    void rejectsNonPositiveRpsDurationAndNonHttpBaseUrl() {
        assertEquals("INVALID_REQUEST", failure(command(0, 50, 70, 100, 30, 5, "http://127.0.0.1:8090")).code());
        assertEquals("rps2 must be positive", failure(command(20, -1, 70, 100, 30, 5, "http://127.0.0.1:8090")).getMessage());
        assertEquals("rampSeconds must be positive", failure(command(20, 50, 70, 100, 30, 0, "http://127.0.0.1:8090")).getMessage());
        assertEquals("stageDurationSeconds must be positive", failure(command(20, 50, 70, 100, 0, 5, "http://127.0.0.1:8090")).getMessage());
        assertTrue(failure(command(20, 50, 70, 100, 30, 5, "ftp://127.0.0.1:8090")).getMessage().contains("baseUrl"));
        assertTrue(failure(command(20, 50, 70, 100, 30, 5, "order-service")).getMessage().contains("baseUrl"));
    }

    @Test
    void rejectsProfileLongerThanTheMaximum() {
        RequestRejectedException failure = failure(command(20, 50, 70, 100, 2000, 1, "http://127.0.0.1:8090"));
        assertEquals("Profile duration exceeds maxProfileDurationSeconds", failure.getMessage());
    }

    @Test
    void boundaryBelongsToTheWindowThatEnds() {
        Instant start = Instant.parse("2026-10-06T09:22:34.073Z");
        List<StagePlan> stages = new WindowPlanner().plan(start, new LoadProfile(20, 50, 70, 100, 300, 60));
        assertEquals(4, stages.size());
        MeasurementWindow hold1 = stages.getFirst().hold();
        MeasurementWindow ramp2 = stages.get(1).ramp();
        assertEquals(WindowType.HOLD, hold1.type());
        assertEquals(hold1.actualEnd(), ramp2.actualStart());
        assertTrue(hold1.contains(hold1.actualEnd()));
        assertTrue(!ramp2.contains(ramp2.actualStart()));
        assertTrue(ramp2.contains(ramp2.actualStart().plusMillis(1)));
        assertEquals(8, stages.stream().mapToInt(stage -> 2).sum());
    }

    @Test
    void runTransitionsAreExplicit() {
        Run run = Run.queued(command(20, 50, 70, 100, 30, 5, "http://127.0.0.1:8090"), Instant.parse("2026-10-06T09:00:00Z"));
        assertEquals(RunStatus.QUEUED, run.status());
        assertThrows(IllegalStateException.class, () -> run.complete(null, Instant.now()));
        run.start(Instant.parse("2026-10-06T09:00:01Z"));
        run.markSimulationStarted(Instant.parse("2026-10-06T09:00:02Z"));
        run.startCollecting();
        assertEquals(RunStatus.COLLECTING, run.status());
    }

    private static RequestRejectedException failure(StartRunCommand command) {
        ProfileValidator validator = new ProfileValidator(Duration.ofHours(2));
        return assertThrows(RequestRejectedException.class, () -> validator.validate(command));
    }

    static StartRunCommand command(
            double rps1, double rps2, double rps3, double rps4, int hold, int ramp, String baseUrl) {
        return new StartRunCommand(
                "outbox-create-order",
                baseUrl,
                rps1,
                rps2,
                rps3,
                rps4,
                hold,
                ramp,
                "http://127.0.0.1:9100",
                List.of(new TelemetryTarget("order-service", "SPRING_BOOT", "order-service")));
    }
}
