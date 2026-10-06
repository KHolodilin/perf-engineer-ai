package com.kholodilin.perfengineer.runprofile.application;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.kholodilin.perfengineer.runprofile.domain.Availability;
import com.kholodilin.perfengineer.runprofile.domain.AvailabilityReason;
import com.kholodilin.perfengineer.runprofile.domain.RunStatus;
import com.kholodilin.perfengineer.runprofile.domain.FailureCode;
import com.kholodilin.perfengineer.runprofile.domain.GatlingAssertions;
import com.kholodilin.perfengineer.runprofile.domain.GatlingTotals;
import com.kholodilin.perfengineer.runprofile.domain.MeasurementWindow;
import com.kholodilin.perfengineer.runprofile.domain.MetricEvidence;
import com.kholodilin.perfengineer.runprofile.domain.Run;
import com.kholodilin.perfengineer.runprofile.domain.RunEvidence;
import com.kholodilin.perfengineer.runprofile.domain.RunId;
import com.kholodilin.perfengineer.runprofile.domain.RunRepository;
import com.kholodilin.perfengineer.runprofile.domain.StageEvidence;
import com.kholodilin.perfengineer.runprofile.domain.StagePlan;
import com.kholodilin.perfengineer.runprofile.domain.TelemetryTarget;
import com.kholodilin.perfengineer.runprofile.domain.WindowEvidence;
import com.kholodilin.perfengineer.runprofile.domain.WindowPlanner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;

@Slf4j
@RequiredArgsConstructor
public class RunExecutor {

    private final RunRepository repository;
    private final WorkloadRegistry workloadRegistry;
    private final WorkloadRunner workloadRunner;
    private final EvidenceParser parser;
    private final AnchorResolver anchorResolver;
    private final WindowPlanner windowPlanner;
    private final TelemetryProvider telemetryProvider;
    private final AdmissionGate admissionGate;
    private final Clock clock;

    public void execute(RunId runId) {
        MDC.put("runId", runId.value());
        try {
            executeObserved(runId);
        } finally {
            admissionGate.release();
            MDC.remove("runId");
        }
    }

    private void executeObserved(RunId runId) {
        Run run = repository.findById(runId).orElse(null);
        if (run == null) {
            log.info("Run failed");
            return;
        }
        MDC.put("targetId", run.command().targetId());
        MDC.put("idempotencyKey", MDC.get("idempotencyKey"));
        try {
            log.info("Run started");
            run.start(clock.instant());
            repository.update(run);
            WorkloadDefinition workload = workloadRegistry.require(run.command().workloadId());
            WorkloadExecutionResult execution = workloadRunner.execute(workload, run.command().profile(), run.command().target());
            log.info("Workload completed");
            if (execution.reportDirectory() == null) {
                FailureCode code = execution.started()
                        ? FailureCode.GATLING_RESULT_UNAVAILABLE
                        : FailureCode.GATLING_START_FAILED;
                fail(run, code, execution.started()
                        ? "Gatling finished without a usable report"
                        : "Gatling process did not start");
                return;
            }
            Optional<GatlingReport> parsed = parser.parse(execution.reportDirectory(), workload);
            if (parsed.isEmpty()) {
                fail(run, FailureCode.RESULT_PARSE_FAILED, "Gatling report could not be parsed");
                return;
            }
            GatlingReport report = parsed.get();
            log.info("Assertions evaluated");
            GatlingAssertions assertions = report.assertions();
            GatlingTotals totals = report.totals();
            Optional<Instant> anchor = execution.anchorReadable()
                    ? anchorResolver.resolve(execution.reportDirectory())
                    : Optional.empty();
            if (anchor.isEmpty()) {
                log.info("Simulation start anchor unavailable");
                run.startCollecting();
                run.complete(RunEvidence.partial(assertions, totals, List.of(), AvailabilityReason.SIMULATION_START_UNAVAILABLE), clock.instant());
                repository.update(run);
                log.info("Run completed");
                return;
            }
            log.info("Simulation start anchor resolved");
            run.markSimulationStarted(anchor.get());
            List<StagePlan> plans = windowPlanner.plan(anchor.get(), run.command().profile());
            run.startCollecting();
            log.info("Telemetry collection started");
            Collection collection = collect(run, plans);
            if (collection.prometheusAvailable()) {
                log.info("Telemetry collection completed");
                run.complete(RunEvidence.full(assertions, totals, collection.stages()), clock.instant());
            } else {
                log.info("Telemetry partially unavailable");
                run.complete(RunEvidence.partial(
                        assertions, totals, collection.stages(), AvailabilityReason.PROMETHEUS_UNAVAILABLE), clock.instant());
            }
            repository.update(run);
            log.info("Run completed");
        } catch (RuntimeException ex) {
            RunStatus status = run.status();
            if (status == RunStatus.QUEUED || status == RunStatus.RUNNING || status == RunStatus.COLLECTING) {
                fail(run, FailureCode.INTERNAL_ERROR, "Run execution failed");
            }
        } finally {
            MDC.remove("targetId");
        }
    }

    private Collection collect(Run run, List<StagePlan> plans) {
        boolean available = true;
        List<StageEvidence> stages = new ArrayList<>();
        for (StagePlan plan : plans) {
            Filled ramp = fill(run, plan.ramp(), available);
            Filled hold = fill(run, plan.hold(), ramp.prometheusAvailable());
            available = hold.prometheusAvailable();
            stages.add(new StageEvidence(plan.stage(), plan.requestedRps(), List.of(ramp.window(), hold.window())));
        }
        return new Collection(List.copyOf(stages), available);
    }

    private Filled fill(Run run, MeasurementWindow window, boolean prometheusAvailable) {
        if (!prometheusAvailable) {
            return new Filled(new WindowEvidence(
                    window.type(), window.actualStart(), window.actualEnd(), telemetryProvider.unavailable(window)), false);
        }
        TelemetryTarget target = run.command().targets().getFirst();
        TelemetryEvidence evidence = telemetryProvider.collect(run.command().prometheusUrl(), target, window);
        if (!evidence.prometheusAvailable()) {
            return new Filled(new WindowEvidence(
                    window.type(), window.actualStart(), window.actualEnd(), telemetryProvider.unavailable(window)), false);
        }
        for (MetricEvidence metric : evidence.metrics()) {
            if (metric.availability() == Availability.UNAVAILABLE) {
                log.info("Metric unavailable");
            }
        }
        return new Filled(new WindowEvidence(window.type(), window.actualStart(), window.actualEnd(), evidence.metrics()), true);
    }

    private void fail(Run run, FailureCode code, String message) {
        run.fail(code, message, clock.instant());
        repository.update(run);
        log.info("Run failed");
    }

    private record Collection(List<StageEvidence> stages, boolean prometheusAvailable) {
    }

    private record Filled(WindowEvidence window, boolean prometheusAvailable) {
    }
}
