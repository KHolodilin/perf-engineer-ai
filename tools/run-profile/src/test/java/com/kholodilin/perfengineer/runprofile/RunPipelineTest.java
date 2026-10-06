package com.kholodilin.perfengineer.runprofile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import com.kholodilin.perfengineer.runprofile.application.AdmissionGate;
import com.kholodilin.perfengineer.runprofile.application.RunExecutor;
import com.kholodilin.perfengineer.runprofile.application.WorkloadDefinition;
import com.kholodilin.perfengineer.runprofile.application.WorkloadExecutionResult;
import com.kholodilin.perfengineer.runprofile.domain.AvailabilityReason;
import com.kholodilin.perfengineer.runprofile.domain.EvidenceStatus;
import com.kholodilin.perfengineer.runprofile.domain.FailureCode;
import com.kholodilin.perfengineer.runprofile.domain.Run;
import com.kholodilin.perfengineer.runprofile.domain.RunStatus;
import com.kholodilin.perfengineer.runprofile.domain.WindowPlanner;
import com.kholodilin.perfengineer.runprofile.infrastructure.gatling.GatlingEvidenceParser;
import com.kholodilin.perfengineer.runprofile.infrastructure.gatling.SimulationAnchorResolver;
import com.kholodilin.perfengineer.runprofile.infrastructure.memory.InMemoryRunRepository;
import com.kholodilin.perfengineer.runprofile.infrastructure.workload.ConfigWorkloadRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RunPipelineTest {

    @Test
    void assertionFailureAndUnreachableTargetStayCompleted(@TempDir Path report) throws Exception {
        Path named = report.resolve("createordersimulation-20261006092234073");
        Files.createDirectories(named);
        Files.writeString(named.resolve("index.html"), reportHtml("KO", 10, 0, 10));
        Run run = execute(named, true, true, 1);
        assertEquals(RunStatus.COMPLETED, run.status());
        assertEquals(EvidenceStatus.FULL, run.evidence().evidenceStatus());
        assertEquals("FAILED", run.evidence().gatlingAssertions().status().name());
        assertEquals(10, run.evidence().gatling().ko());
        assertEquals(8, run.evidence().stages().stream().mapToInt(stage -> stage.windows().size()).sum());
        assertNull(run.evidence().partialReason());
    }

    @Test
    void missingAnchorKeepsGatlingAndMarksWindowsUnavailable(@TempDir Path report) throws Exception {
        Path named = report.resolve("createordersimulation-not-a-timestamp");
        Files.createDirectories(named);
        Files.writeString(named.resolve("index.html"), reportHtml("OK", 4, 4, 0));
        Run run = execute(named, false, true, 0);
        assertEquals(RunStatus.COMPLETED, run.status());
        assertEquals(EvidenceStatus.PARTIAL, run.evidence().evidenceStatus());
        assertEquals(AvailabilityReason.SIMULATION_START_UNAVAILABLE, run.evidence().partialReason());
        assertEquals(4, run.evidence().gatling().requests());
        assertTrue(run.evidence().stages().isEmpty());
        assertNull(run.simulationStartedAt());
    }

    @Test
    void prometheusOutageDoesNotEraseGatling(@TempDir Path report) throws Exception {
        Path named = report.resolve("createordersimulation-20261006092234073");
        Files.createDirectories(named);
        Files.writeString(named.resolve("index.html"), reportHtml("OK", 4, 4, 0));
        Run run = execute(named, true, false, 0);
        assertEquals(RunStatus.COMPLETED, run.status());
        assertEquals(EvidenceStatus.PARTIAL, run.evidence().evidenceStatus());
        assertEquals(AvailabilityReason.PROMETHEUS_UNAVAILABLE, run.evidence().partialReason());
        assertEquals(4, run.evidence().gatling().ok());
        assertEquals(8, run.evidence().stages().stream().mapToInt(stage -> stage.windows().size()).sum());
    }

    @Test
    void missingProcessIsATerminalFailure() {
        Run run = execute(null, false, true, -1, false);
        assertEquals(RunStatus.FAILED, run.status());
        assertEquals(FailureCode.GATLING_START_FAILED, run.failure().code());
    }

    private static Run execute(Path report, boolean anchorReadable, boolean prometheusUp, int exitCode) {
        return execute(report, anchorReadable, prometheusUp, exitCode, true);
    }

    private static Run execute(Path report, boolean anchorReadable, boolean prometheusUp, int exitCode, boolean started) {
        Clock clock = Clock.fixed(Instant.parse("2026-10-06T09:00:00Z"), ZoneOffset.UTC);
        InMemoryRunRepository repository = new InMemoryRunRepository(java.time.Duration.ofHours(4), clock);
        AdmissionGate gate = new AdmissionGate(1, 1);
        assertTrue(gate.tryAcquire());
        Run run = Run.queued(ProfileAndWindowTest.command(20, 50, 70, 100, 30, 5, "http://127.0.0.1:8090"), clock.instant());
        repository.save(run);
        AtomicInteger telemetryCalls = new AtomicInteger();
        RunExecutor executor = new RunExecutor(
                repository,
                new ConfigWorkloadRegistry(Map.of("outbox-create-order", new WorkloadDefinition(
                        "outbox-create-order", Path.of("."), "load-tests",
                        "com.kholodilin.outbox.loadtests.CreateOrderSimulation", "steps", true, 0.5, 200))),
                (workload, profile, target) -> new WorkloadExecutionResult(
                        clock.instant(), clock.instant(), exitCode, started, report, anchorReadable),
                new GatlingEvidenceParser(),
                new SimulationAnchorResolver(ObservationRegistry.create()),
                new WindowPlanner(),
                new com.kholodilin.perfengineer.runprofile.application.TelemetryProvider() {
                    @Override
                    public com.kholodilin.perfengineer.runprofile.application.TelemetryEvidence collect(
                            String prometheusUrl,
                            com.kholodilin.perfengineer.runprofile.domain.TelemetryTarget target,
                            com.kholodilin.perfengineer.runprofile.domain.MeasurementWindow window) {
                        telemetryCalls.incrementAndGet();
                        if (!prometheusUp) {
                            return new com.kholodilin.perfengineer.runprofile.application.TelemetryEvidence(List.of(), false);
                        }
                        return new com.kholodilin.perfengineer.runprofile.application.TelemetryEvidence(List.of(), true);
                    }

                    @Override
                    public List<com.kholodilin.perfengineer.runprofile.domain.MetricEvidence> unavailable(
                            com.kholodilin.perfengineer.runprofile.domain.MeasurementWindow window) {
                        return List.of();
                    }
                },
                gate,
                clock);
        executor.execute(run.runId());
        if (!prometheusUp) {
            assertEquals(1, telemetryCalls.get());
        }
        return repository.findById(run.runId()).orElseThrow();
    }

    private static String reportHtml(String assertion, long requests, long ok, long ko) {
        return """
                <table id="container_assertions"><td class="error-col-2">%s</td></table>
                <tr id="ROOT">
                  <td class="value total col-2">%d</td>
                  <td class="value ok col-3">%d</td>
                  <td class="value ko col-4">%d</td>
                </tr>
                """.formatted(assertion, requests, ok, ko);
    }
}
