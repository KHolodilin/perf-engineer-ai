package com.kholodilin.perfengineer.runprofile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.kholodilin.perfengineer.runprofile.domain.Availability;
import com.kholodilin.perfengineer.runprofile.domain.AvailabilityReason;
import com.kholodilin.perfengineer.runprofile.domain.MeasurementWindow;
import com.kholodilin.perfengineer.runprofile.domain.MetricEvidence;
import com.kholodilin.perfengineer.runprofile.domain.MetricSemantics;
import com.kholodilin.perfengineer.runprofile.domain.TelemetryTarget;
import com.kholodilin.perfengineer.runprofile.domain.WindowType;
import com.kholodilin.perfengineer.runprofile.infrastructure.prometheus.MetricMeasurabilityPolicy;
import com.kholodilin.perfengineer.runprofile.infrastructure.prometheus.PrometheusClient;
import com.kholodilin.perfengineer.runprofile.infrastructure.prometheus.SpringBootEvidenceMapper;
import com.kholodilin.perfengineer.runprofile.infrastructure.prometheus.VectorResult;
import com.kholodilin.perfengineer.runprofile.infrastructure.prometheus.VectorSample;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;

class PrometheusEvidenceTest {

    private final TelemetryTarget target = new TelemetryTarget("order-service", "SPRING_BOOT", "order-service");

    @Test
    void shortRampIsRejectedBeforeQueryAndDoesNotReadTheNextHold() {
        Instant start = Instant.parse("2026-10-06T09:22:34Z");
        MeasurementWindow ramp = new MeasurementWindow(WindowType.RAMP, start, start.plusSeconds(5));
        MeasurementWindow hold = new MeasurementWindow(WindowType.HOLD, ramp.actualEnd(), ramp.actualEnd().plusSeconds(300));
        RecordingClient client = new RecordingClient((promql, time) -> VectorResult.empty());
        SpringBootEvidenceMapper mapper = mapper(client);
        List<MetricEvidence> metrics = mapper.collect("http://127.0.0.1:9100", target, ramp, Duration.ofSeconds(15)).metrics();
        assertEquals(AvailabilityReason.INSUFFICIENT_SAMPLES, metric(metrics, "httpLatencyP99").reason());
        assertEquals(AvailabilityReason.INSUFFICIENT_SAMPLES, metric(metrics, "bulkheadRejectDelta").reason());
        assertTrue(client.times.stream().allMatch(ramp.actualEnd()::equals));
        assertTrue(client.queries.stream().noneMatch(query -> query.contains("count_over_time")
                || query.contains("rate(") || query.contains("increase(") || query.contains("histogram_quantile")));
        assertTrue(client.times.stream().noneMatch(hold.actualEnd()::equals));
        assertTrue(metrics.stream().anyMatch(metric -> "http_server_requests_seconds_bucket".equals(metric.series())
                && metric.selector().contains("status=\"201\"")));
    }

    @Test
    void sampleCountComesFromTheSourceSeriesValue() {
        RecordingClient client = new RecordingClient((promql, time) -> {
            if (promql.startsWith("count_over_time(http_server_requests_seconds_count")) {
                return sample(10);
            }
            if (promql.startsWith("rate(http_server_requests_seconds_count")) {
                return sample(4.5);
            }
            return sample(3);
        });
        MetricEvidence achieved = metric(collect(client, 300).metrics(), "httpAchievedRps");
        assertEquals(Availability.AVAILABLE, achieved.availability());
        assertEquals(4.5, achieved.value());
        assertEquals("http_server_requests_seconds_count", achieved.series());
    }

    @Test
    void fewerThanTwoRawSamplesRejectsAnExtrapolatedRate() {
        RecordingClient client = new RecordingClient((promql, time) -> {
            if (promql.startsWith("count_over_time")) {
                return sample(1);
            }
            return sample(9);
        });
        MetricEvidence achieved = metric(collect(client, 300).metrics(), "httpAchievedRps");
        assertEquals(AvailabilityReason.INSUFFICIENT_SAMPLES, achieved.reason());
        assertNull(achieved.value());
    }

    @Test
    void histogramNeedsBucketsAndCountAndKeepsNanUnavailable() {
        RecordingClient missingCount = new RecordingClient((promql, time) -> {
            if (promql.contains("http_server_requests_seconds_count")) {
                return VectorResult.empty();
            }
            return sample(4);
        });
        assertEquals(AvailabilityReason.SERIES_NOT_FOUND, metric(collect(missingCount, 300).metrics(), "httpLatencyP99").reason());

        RecordingClient nan = new RecordingClient((promql, time) -> {
            if (promql.startsWith("histogram_quantile")) {
                return new VectorResult(List.of(new VectorSample(Map.of(), Instant.EPOCH, Double.NaN)));
            }
            return sample(4);
        });
        MetricEvidence latency = metric(collect(nan, 300).metrics(), "httpLatencyP99");
        assertEquals(AvailabilityReason.NOT_A_NUMBER, latency.reason());
        assertNull(latency.value());
        assertTrue(latency.selector().contains("status=\"201\""));
    }

    @Test
    void gaugeLookbackAndUsageMaxOrigin() {
        Instant start = Instant.parse("2026-10-06T09:22:34Z");
        Instant end = start.plusSeconds(300);
        RecordingClient outside = new RecordingClient((promql, time) -> {
            if (promql.startsWith("hikaricp_connections_active")) {
                return new VectorResult(List.of(new VectorSample(Map.of(), start.minusSeconds(10), 2)));
            }
            if (promql.startsWith("hikaricp_connections_pending")) {
                return VectorResult.empty();
            }
            if (promql.startsWith("hikaricp_connections_usage_seconds_max")) {
                return new VectorResult(List.of(new VectorSample(Map.of(), end, 0.2)));
            }
            return sample(4);
        });
        List<MetricEvidence> metrics = mapper(outside)
                .collect("http://127.0.0.1:9100", target, new MeasurementWindow(WindowType.HOLD, start, end), Duration.ofSeconds(15))
                .metrics();
        assertEquals(AvailabilityReason.SAMPLE_OUTSIDE_WINDOW, metric(metrics, "hikariConnectionsActive").reason());
        assertEquals(AvailabilityReason.SERIES_NOT_FOUND, metric(metrics, "hikariConnectionsPending").reason());
        MetricEvidence usageMax = metric(metrics, "hikariUsageMax");
        assertEquals(MetricSemantics.MAX_OBSERVED_GAUGE, usageMax.semantics());
        assertEquals(Boolean.FALSE, usageMax.windowScopedOrigin());
        assertEquals(0.2, usageMax.value());
        assertTrue(metrics.stream().map(MetricEvidence::series).toList().containsAll(List.of(
                "http_server_requests_seconds_count",
                "http_server_requests_seconds_bucket",
                "hikaricp_connections_acquire_seconds",
                "hikaricp_connections_usage_seconds",
                "order_bulkhead_rejects_total",
                "outbox_rate_limit_rejects_total",
                "outbox_pool_exhausted_rejects_total",
                "system_cpu_usage",
                "process_cpu_usage")));
    }

    @Test
    void policyRejectsARangeLongerThanTheWindow() {
        MetricMeasurabilityPolicy policy = new MetricMeasurabilityPolicy();
        assertTrue(!policy.accepts(Duration.ofSeconds(60), Duration.ofSeconds(90), Duration.ofSeconds(15)));
        assertTrue(policy.accepts(Duration.ofSeconds(60), Duration.ofSeconds(60), Duration.ofSeconds(15)));
    }

    private com.kholodilin.perfengineer.runprofile.application.TelemetryEvidence collect(RecordingClient client, int seconds) {
        Instant start = Instant.parse("2026-10-06T09:22:34Z");
        return mapper(client).collect(
                "http://127.0.0.1:9100",
                target,
                new MeasurementWindow(WindowType.HOLD, start, start.plusSeconds(seconds)),
                Duration.ofSeconds(15));
    }

    private static SpringBootEvidenceMapper mapper(PrometheusClient client) {
        return new SpringBootEvidenceMapper(client, new MetricMeasurabilityPolicy(), ObservationRegistry.create());
    }

    private static MetricEvidence metric(List<MetricEvidence> metrics, String name) {
        return metrics.stream().filter(metric -> metric.name().equals(name)).findFirst().orElseThrow();
    }

    private static VectorResult sample(double value) {
        return new VectorResult(List.of(new VectorSample(Map.of("job", "order-service"), Instant.EPOCH, value)));
    }

    @FunctionalInterface
    interface Script {
        VectorResult answer(String promql, Instant time);
    }

    static final class RecordingClient implements PrometheusClient {
        private final Script script;
        private final List<String> queries = new ArrayList<>();
        private final List<Instant> times = new ArrayList<>();

        RecordingClient(Script script) {
            this.script = script;
        }

        @Override
        public VectorResult query(String prometheusUrl, String promql, Instant evaluationTime) {
            assertTrue(!promql.contains(" @ "));
            queries.add(promql);
            times.add(evaluationTime);
            return script.answer(promql, evaluationTime);
        }
    }
}
