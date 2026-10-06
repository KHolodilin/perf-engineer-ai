package com.kholodilin.perfengineer.runprofile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicReference;

import com.kholodilin.perfengineer.runprofile.application.ProcessOutcome;
import com.kholodilin.perfengineer.runprofile.application.WorkloadDefinition;
import com.kholodilin.perfengineer.runprofile.domain.AssertionStatus;
import com.kholodilin.perfengineer.runprofile.domain.LoadProfile;
import com.kholodilin.perfengineer.runprofile.domain.Target;
import com.kholodilin.perfengineer.runprofile.infrastructure.gatling.GatlingEvidenceParser;
import com.kholodilin.perfengineer.runprofile.infrastructure.gatling.GatlingWorkloadRunner;
import com.kholodilin.perfengineer.runprofile.infrastructure.gatling.ReportDirectorySelector;
import com.kholodilin.perfengineer.runprofile.infrastructure.gatling.SimulationAnchorResolver;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GatlingEvidenceTest {

    private final SimulationAnchorResolver resolver = new SimulationAnchorResolver(ObservationRegistry.create());

    @Test
    void parsesDirectoryTimestampAsUtcRegardlessOfJvmZone() {
        TimeZone original = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Honolulu"));
            Instant anchor = resolver.resolve(Path.of("createordersimulation-20261006092234073")).orElseThrow();
            assertEquals(Instant.parse("2026-10-06T09:22:34.073Z"), anchor);
        } finally {
            TimeZone.setDefault(original);
        }
    }

    @Test
    void unreadableNameDoesNotFallBackToMavenStart() {
        assertTrue(resolver.resolve(Path.of("createordersimulation-not-a-timestamp")).isEmpty());
    }

    @Test
    void selectsTheEarliestDirectoryAtOrAfterMavenStart(@TempDir Path root) throws Exception {
        Path gatling = Files.createDirectories(root.resolve("gatling"));
        Files.createDirectory(gatling.resolve("createordersimulation-20261006092200000"));
        Files.createDirectory(gatling.resolve("createordersimulation-20261006092234073"));
        Files.createDirectory(gatling.resolve("createordersimulation-20261006092300000"));
        ReportDirectorySelector selector = new ReportDirectorySelector(resolver);
        Instant mavenStart = Instant.parse("2026-10-06T09:22:34.073Z");
        Path selected = selector.selectAnchored(gatling, "createordersimulation-", mavenStart).orElseThrow();
        assertEquals("createordersimulation-20261006092234073", selected.getFileName().toString());
    }

    @Test
    void parsesRootTotalsAndAssertionFailure(@TempDir Path root) throws Exception {
        Path report = Files.createDirectories(root.resolve("createordersimulation-20261006092234073"));
        Files.writeString(report.resolve("index.html"), """
                <table id="container_assertions"><tr><td class="error-col-2 value ko total">KO</td></tr></table>
                <tr id="ROOT">
                  <td class="value total col-2">14925</td>
                  <td class="value ok col-3">14318</td>
                  <td class="value ko col-4">607</td>
                </tr>
                """);
        var parsed = new GatlingEvidenceParser().parse(report, workload(root)).orElseThrow();
        assertEquals(14925, parsed.totals().requests());
        assertEquals(14318, parsed.totals().ok());
        assertEquals(607, parsed.totals().ko());
        assertEquals(AssertionStatus.FAILED, parsed.assertions().status());
        assertEquals(0.5, parsed.assertions().maxFailedPercent());
        assertEquals(200, parsed.assertions().p95Ms());
    }

    @Test
    void launchesMavenWithSeparateArguments(@TempDir Path reactor) {
        AtomicReference<List<String>> captured = new AtomicReference<>();
        GatlingWorkloadRunner runner = new GatlingWorkloadRunner(
                (command, directory) -> {
                    captured.set(command);
                    try {
                        String digits = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS")
                                .withZone(ZoneOffset.UTC)
                                .format(Instant.now());
                        Path report = reactor.resolve("load-tests").resolve("target").resolve("gatling")
                                .resolve("createordersimulation-" + digits);
                        Files.createDirectories(report);
                        Files.writeString(report.resolve("index.html"), "<tr id=\"ROOT\"></tr>");
                    } catch (java.io.IOException ex) {
                        throw new java.io.UncheckedIOException(ex);
                    }
                    return new ProcessOutcome(1, true);
                },
                new ReportDirectorySelector(resolver),
                java.time.Clock.systemUTC(),
                ObservationRegistry.create());
        runner.execute(workload(reactor), new LoadProfile(20, 50, 70, 100, 30, 5), new Target("http://127.0.0.1:8090"));
        List<String> command = captured.get();
        assertEquals(List.of(
                "mvn",
                "-pl",
                "load-tests",
                "gatling:test",
                "-Dgatling.simulationClass=com.kholodilin.outbox.loadtests.CreateOrderSimulation",
                "-Dprofile=steps",
                "-DbaseUrl=http://127.0.0.1:8090",
                "-Drps1=20",
                "-Drps2=50",
                "-Drps3=70",
                "-Drps4=100",
                "-DstageDurationSeconds=30",
                "-DrampSeconds=5"), command);
        assertFalse(command.stream().anyMatch(argument -> argument.contains(" && ")));
    }

    private static WorkloadDefinition workload(Path reactor) {
        return new WorkloadDefinition(
                "outbox-create-order",
                reactor,
                "load-tests",
                "com.kholodilin.outbox.loadtests.CreateOrderSimulation",
                "steps",
                true,
                0.5,
                200);
    }
}
