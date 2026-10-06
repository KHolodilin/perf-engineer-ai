package com.kholodilin.perfengineer.runprofile.infrastructure.gatling;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.kholodilin.perfengineer.runprofile.application.ProcessOutcome;
import com.kholodilin.perfengineer.runprofile.application.WorkloadDefinition;
import com.kholodilin.perfengineer.runprofile.application.WorkloadExecutionResult;
import com.kholodilin.perfengineer.runprofile.application.WorkloadRunner;
import com.kholodilin.perfengineer.runprofile.domain.LoadProfile;
import com.kholodilin.perfengineer.runprofile.domain.Target;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public final class GatlingWorkloadRunner implements WorkloadRunner {

    private final ProcessRunner processRunner;
    private final ReportDirectorySelector selector;
    private final Clock clock;
    private final ObservationRegistry observationRegistry;

    public GatlingWorkloadRunner(
            ProcessRunner processRunner,
            ReportDirectorySelector selector,
            Clock clock,
            ObservationRegistry observationRegistry) {
        this.processRunner = processRunner;
        this.selector = selector;
        this.clock = clock;
        this.observationRegistry = observationRegistry;
    }

    @Override
    public WorkloadExecutionResult execute(WorkloadDefinition workload, LoadProfile profile, Target target) {
        return Observation.createNotStarted("workload.execute", observationRegistry).observe(() -> {
            Instant started = clock.instant();
            List<String> command = command(workload, profile, target);
            log.info("Maven process started");
            ProcessOutcome outcome = processRunner.run(command, workload.reactorRoot());
            Instant finished = clock.instant();
            Path gatlingDirectory = workload.reactorRoot()
                    .resolve(workload.module())
                    .resolve("target")
                    .resolve("gatling");
            Optional<Path> anchored = selector.selectAnchored(gatlingDirectory, workload.reportNamePrefix(), started);
            if (anchored.isPresent()) {
                return new WorkloadExecutionResult(started, finished, outcome.exitCode(), outcome.started(), anchored.get(), true);
            }
            Optional<Path> unreadable = selector.selectUnreadable(gatlingDirectory, workload.reportNamePrefix(), started);
            return new WorkloadExecutionResult(
                    started,
                    finished,
                    outcome.exitCode(),
                    outcome.started(),
                    unreadable.orElse(null),
                    false);
        });
    }

    static List<String> command(WorkloadDefinition workload, LoadProfile profile, Target target) {
        List<String> command = new ArrayList<>();
        command.add("mvn");
        command.add("-pl");
        command.add(workload.module());
        command.add("gatling:test");
        command.add("-Dgatling.simulationClass=" + workload.simulationClass());
        command.add("-Dprofile=" + workload.profile());
        command.add("-DbaseUrl=" + target.baseUrl());
        command.add("-Drps1=" + number(profile.rps1()));
        command.add("-Drps2=" + number(profile.rps2()));
        command.add("-Drps3=" + number(profile.rps3()));
        command.add("-Drps4=" + number(profile.rps4()));
        command.add("-DstageDurationSeconds=" + profile.stageDurationSeconds());
        command.add("-DrampSeconds=" + profile.rampSeconds());
        return List.copyOf(command);
    }

    private static String number(double value) {
        if (!Double.isInfinite(value) && value == Math.rint(value)) {
            return Long.toString((long) value);
        }
        return Double.toString(value);
    }
}
