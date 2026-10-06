package com.kholodilin.perfengineer.runprofile.infrastructure.prometheus;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.kholodilin.perfengineer.runprofile.application.TelemetryEvidence;
import com.kholodilin.perfengineer.runprofile.domain.Availability;
import com.kholodilin.perfengineer.runprofile.domain.AvailabilityReason;
import com.kholodilin.perfengineer.runprofile.domain.MeasurementWindow;
import com.kholodilin.perfengineer.runprofile.domain.MetricEvidence;
import com.kholodilin.perfengineer.runprofile.domain.MetricSemantics;
import com.kholodilin.perfengineer.runprofile.domain.TelemetryTarget;
import com.kholodilin.perfengineer.runprofile.domain.WindowType;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;

public final class SpringBootEvidenceMapper {

    private final PrometheusClient client;
    private final MetricMeasurabilityPolicy policy;
    private final ObservationRegistry observationRegistry;

    public SpringBootEvidenceMapper(
            PrometheusClient client,
            MetricMeasurabilityPolicy policy,
            ObservationRegistry observationRegistry) {
        this.client = client;
        this.policy = policy;
        this.observationRegistry = observationRegistry;
    }

    public TelemetryEvidence collect(
            String prometheusUrl,
            TelemetryTarget target,
            MeasurementWindow window,
            Duration scrapeInterval) {
        try {
            List<MetricEvidence> metrics = new ArrayList<>();
            String job = target.job();
            Duration duration = window.duration();
            boolean measurable = policy.accepts(duration, duration, scrapeInterval);
            metrics.add(counterRate(
                    prometheusUrl, window, measurable, "httpAchievedRps", "requests_per_second",
                    "http_server_requests_seconds_count", jobSelector(job)));
            metrics.addAll(httpLatency(prometheusUrl, window, measurable, job));
            metrics.add(gauge(prometheusUrl, window, "hikariConnectionsActive", "connections",
                    "hikaricp_connections_active", jobSelector(job), MetricSemantics.GAUGE_SAMPLE, null));
            metrics.add(gauge(prometheusUrl, window, "hikariConnectionsPending", "connections",
                    "hikaricp_connections_pending", jobSelector(job), MetricSemantics.GAUGE_SAMPLE, null));
            metrics.add(histogramQuantile(
                    prometheusUrl, window, measurable, "hikariAcquireP99", "seconds",
                    "hikaricp_connections_acquire_seconds", jobSelector(job), 0.99));
            metrics.add(histogramMean(
                    prometheusUrl, window, measurable, "hikariUsageMean", "seconds",
                    "hikaricp_connections_usage_seconds", jobSelector(job)));
            metrics.add(gauge(prometheusUrl, window, "hikariUsageMax", "seconds",
                    "hikaricp_connections_usage_seconds_max", jobSelector(job), MetricSemantics.MAX_OBSERVED_GAUGE, false));
            metrics.add(counterIncrease(prometheusUrl, window, measurable, "bulkheadRejectDelta",
                    "order_bulkhead_rejects_total", job));
            metrics.add(counterIncrease(prometheusUrl, window, measurable, "rateLimitRejectDelta",
                    "outbox_rate_limit_rejects_total", job));
            metrics.add(counterIncrease(prometheusUrl, window, measurable, "poolExhaustedRejectDelta",
                    "outbox_pool_exhausted_rejects_total", job));
            metrics.add(gauge(prometheusUrl, window, "systemCpuUsage", "ratio",
                    "system_cpu_usage", jobSelector(job), MetricSemantics.GAUGE_SAMPLE, null));
            metrics.add(gauge(prometheusUrl, window, "processCpuUsage", "ratio",
                    "process_cpu_usage", jobSelector(job), MetricSemantics.GAUGE_SAMPLE, null));
            return new TelemetryEvidence(metrics, true);
        } catch (PrometheusUnavailableException ex) {
            return new TelemetryEvidence(List.of(), false);
        }
    }

    public List<MetricEvidence> unavailable(MeasurementWindow window, AvailabilityReason reason) {
        List<MetricEvidence> metrics = new ArrayList<>();
        WindowType scope = window.type();
        metrics.add(missing("httpAchievedRps", "requests_per_second", "http_server_requests_seconds_count",
                "job=\"job\"", MetricSemantics.COUNTER_RATE, "rate()", true, reason, scope, null));
        metrics.add(missing("httpLatencyMean", "seconds", "http_server_requests_seconds_bucket",
                "status=\"201\"", MetricSemantics.HISTOGRAM_MEAN, "rate(sum)/rate(count)", true, reason, scope, null));
        metrics.add(missing("httpLatencyP95", "seconds", "http_server_requests_seconds_bucket",
                "status=\"201\"", MetricSemantics.HISTOGRAM_QUANTILE, "histogram_quantile(rate())", true, reason, scope, null));
        metrics.add(missing("httpLatencyP99", "seconds", "http_server_requests_seconds_bucket",
                "status=\"201\"", MetricSemantics.HISTOGRAM_QUANTILE, "histogram_quantile(rate())", true, reason, scope, null));
        metrics.add(missing("hikariConnectionsActive", "connections", "hikaricp_connections_active",
                "job=\"job\"", MetricSemantics.GAUGE_SAMPLE, "gauge", false, reason, scope, null));
        metrics.add(missing("hikariConnectionsPending", "connections", "hikaricp_connections_pending",
                "job=\"job\"", MetricSemantics.GAUGE_SAMPLE, "gauge", false, reason, scope, null));
        metrics.add(missing("hikariAcquireP99", "seconds", "hikaricp_connections_acquire_seconds",
                "job=\"job\"", MetricSemantics.HISTOGRAM_QUANTILE, "histogram_quantile(rate())", true, reason, scope, null));
        metrics.add(missing("hikariUsageMean", "seconds", "hikaricp_connections_usage_seconds",
                "job=\"job\"", MetricSemantics.HISTOGRAM_MEAN, "rate(sum)/rate(count)", true, reason, scope, null));
        metrics.add(missing("hikariUsageMax", "seconds", "hikaricp_connections_usage_seconds_max",
                "job=\"job\"", MetricSemantics.MAX_OBSERVED_GAUGE, "gauge", false, reason, scope, false));
        metrics.add(missing("bulkheadRejectDelta", "count", "order_bulkhead_rejects_total",
                "job=\"job\"", MetricSemantics.COUNTER_DELTA, "increase()", true, reason, scope, null));
        metrics.add(missing("rateLimitRejectDelta", "count", "outbox_rate_limit_rejects_total",
                "job=\"job\"", MetricSemantics.COUNTER_DELTA, "increase()", true, reason, scope, null));
        metrics.add(missing("poolExhaustedRejectDelta", "count", "outbox_pool_exhausted_rejects_total",
                "job=\"job\"", MetricSemantics.COUNTER_DELTA, "increase()", true, reason, scope, null));
        metrics.add(missing("systemCpuUsage", "ratio", "system_cpu_usage",
                "job=\"job\"", MetricSemantics.GAUGE_SAMPLE, "gauge", false, reason, scope, null));
        metrics.add(missing("processCpuUsage", "ratio", "process_cpu_usage",
                "job=\"job\"", MetricSemantics.GAUGE_SAMPLE, "gauge", false, reason, scope, null));
        return List.copyOf(metrics);
    }

    private List<MetricEvidence> httpLatency(String url, MeasurementWindow window, boolean measurable, String job) {
        String selector = "job=\"" + escape(job) + "\",status=\"201\"";
        String display = "status=\"201\"";
        if (!measurable) {
            return List.of(
                    missing("httpLatencyMean", "seconds", "http_server_requests_seconds_bucket", display,
                            MetricSemantics.HISTOGRAM_MEAN, "rate(sum)/rate(count)", true,
                            AvailabilityReason.INSUFFICIENT_SAMPLES, window.type(), null),
                    missing("httpLatencyP95", "seconds", "http_server_requests_seconds_bucket", display,
                            MetricSemantics.HISTOGRAM_QUANTILE, "histogram_quantile(rate())", true,
                            AvailabilityReason.INSUFFICIENT_SAMPLES, window.type(), null),
                    missing("httpLatencyP99", "seconds", "http_server_requests_seconds_bucket", display,
                            MetricSemantics.HISTOGRAM_QUANTILE, "histogram_quantile(rate())", true,
                            AvailabilityReason.INSUFFICIENT_SAMPLES, window.type(), null));
        }
        String range = range(window.duration());
        VectorResult buckets = sampleCount(url, "http_server_requests_seconds_bucket{" + selector + "}", range, window.actualEnd());
        VectorResult count = sampleCount(url, "http_server_requests_seconds_count{" + selector + "}", range, window.actualEnd());
        VectorResult sum = sampleCount(url, "http_server_requests_seconds_sum{" + selector + "}", range, window.actualEnd());
        AvailabilityReason quantileReason = histogramReason(buckets, count);
        AvailabilityReason meanReason = histogramReason(sum, count);
        List<MetricEvidence> metrics = new ArrayList<>();
        if (meanReason != null) {
            metrics.add(missing("httpLatencyMean", "seconds", "http_server_requests_seconds_bucket", display,
                    MetricSemantics.HISTOGRAM_MEAN, "rate(sum)/rate(count)", true, meanReason, window.type(), null));
        } else {
            String promql = "sum(rate(http_server_requests_seconds_sum{" + selector + "}" + range + ")) / sum(rate(http_server_requests_seconds_count{"
                    + selector + "}" + range + "))";
            metrics.add(fromDerived(url, window, "httpLatencyMean", "seconds", "http_server_requests_seconds_bucket",
                    display, MetricSemantics.HISTOGRAM_MEAN, "rate(sum)/rate(count)", promql, null));
        }
        if (quantileReason != null) {
            metrics.add(missing("httpLatencyP95", "seconds", "http_server_requests_seconds_bucket", display,
                    MetricSemantics.HISTOGRAM_QUANTILE, "histogram_quantile(rate())", true, quantileReason, window.type(), null));
            metrics.add(missing("httpLatencyP99", "seconds", "http_server_requests_seconds_bucket", display,
                    MetricSemantics.HISTOGRAM_QUANTILE, "histogram_quantile(rate())", true, quantileReason, window.type(), null));
        } else {
            metrics.add(quantile(url, window, "httpLatencyP95", selector, display, 0.95));
            metrics.add(quantile(url, window, "httpLatencyP99", selector, display, 0.99));
        }
        return metrics;
    }

    private MetricEvidence counterRate(
            String url, MeasurementWindow window, boolean measurable, String name, String unit, String series, String selector) {
        if (!measurable) {
            return missing(name, unit, series, selector, MetricSemantics.COUNTER_RATE, "rate()", true,
                    AvailabilityReason.INSUFFICIENT_SAMPLES, window.type(), null);
        }
        return derivedCounter(url, window, name, unit, series, selector, MetricSemantics.COUNTER_RATE, "rate()", "rate");
    }

    private MetricEvidence counterIncrease(
            String url, MeasurementWindow window, boolean measurable, String name, String series, String job) {
        String selector = jobSelector(job);
        if (!measurable) {
            return missing(name, "count", series, selector, MetricSemantics.COUNTER_DELTA, "increase()", true,
                    AvailabilityReason.INSUFFICIENT_SAMPLES, window.type(), null);
        }
        return derivedCounter(url, window, name, "count", series, selector, MetricSemantics.COUNTER_DELTA, "increase()", "increase");
    }

    private MetricEvidence derivedCounter(
            String url,
            MeasurementWindow window,
            String name,
            String unit,
            String series,
            String selector,
            MetricSemantics semantics,
            String method,
            String function) {
        String range = range(window.duration());
        String source = series + "{" + selector + "}";
        VectorResult counted = sampleCount(url, source, range, window.actualEnd());
        if (counted.samples().isEmpty()) {
            return missing(name, unit, series, selector, semantics, method, true,
                    AvailabilityReason.SERIES_NOT_FOUND, window.type(), null);
        }
        double samples = counted.samples().getFirst().value();
        if (samples < 2) {
            return missing(name, unit, series, selector, semantics, method, true,
                    AvailabilityReason.INSUFFICIENT_SAMPLES, window.type(), null);
        }
        String promql = function + "(" + source + range + ")";
        return fromDerived(url, window, name, unit, series, selector, semantics, method, promql, null);
    }

    private MetricEvidence histogramQuantile(
            String url, MeasurementWindow window, boolean measurable, String name, String unit, String series, String selector, double quantile) {
        if (!measurable) {
            return missing(name, unit, series, selector, MetricSemantics.HISTOGRAM_QUANTILE, "histogram_quantile(rate())", true,
                    AvailabilityReason.INSUFFICIENT_SAMPLES, window.type(), null);
        }
        String range = range(window.duration());
        VectorResult buckets = sampleCount(url, series + "_bucket{" + selector + "}", range, window.actualEnd());
        VectorResult count = sampleCount(url, series + "_count{" + selector + "}", range, window.actualEnd());
        AvailabilityReason reason = histogramReason(buckets, count);
        if (reason != null) {
            return missing(name, unit, series, selector, MetricSemantics.HISTOGRAM_QUANTILE, "histogram_quantile(rate())", true,
                    reason, window.type(), null);
        }
        String promql = "histogram_quantile(" + quantile + ", sum by (le) (rate(" + series + "_bucket{" + selector + "}" + range + ")))";
        return fromDerived(url, window, name, unit, series, selector, MetricSemantics.HISTOGRAM_QUANTILE,
                "histogram_quantile(rate())", promql, null);
    }

    private MetricEvidence histogramMean(
            String url, MeasurementWindow window, boolean measurable, String name, String unit, String series, String selector) {
        if (!measurable) {
            return missing(name, unit, series, selector, MetricSemantics.HISTOGRAM_MEAN, "rate(sum)/rate(count)", true,
                    AvailabilityReason.INSUFFICIENT_SAMPLES, window.type(), null);
        }
        String range = range(window.duration());
        VectorResult sum = sampleCount(url, series + "_sum{" + selector + "}", range, window.actualEnd());
        VectorResult count = sampleCount(url, series + "_count{" + selector + "}", range, window.actualEnd());
        AvailabilityReason reason = histogramReason(sum, count);
        if (reason != null) {
            return missing(name, unit, series, selector, MetricSemantics.HISTOGRAM_MEAN, "rate(sum)/rate(count)", true,
                    reason, window.type(), null);
        }
        String promql = "sum(rate(" + series + "_sum{" + selector + "}" + range + ")) / sum(rate(" + series + "_count{"
                + selector + "}" + range + "))";
        return fromDerived(url, window, name, unit, series, selector, MetricSemantics.HISTOGRAM_MEAN, "rate(sum)/rate(count)", promql, null);
    }

    private MetricEvidence quantile(String url, MeasurementWindow window, String name, String selector, String display, double q) {
        String range = range(window.duration());
        String promql = "histogram_quantile(" + q + ", sum by (le) (rate(http_server_requests_seconds_bucket{" + selector + "}" + range + ")))";
        return fromDerived(url, window, name, "seconds", "http_server_requests_seconds_bucket", display,
                MetricSemantics.HISTOGRAM_QUANTILE, "histogram_quantile(rate())", promql, null);
    }

    private MetricEvidence gauge(
            String url,
            MeasurementWindow window,
            String name,
            String unit,
            String series,
            String selector,
            MetricSemantics semantics,
            Boolean windowScopedOrigin) {
        String promql = series + "{" + selector + "}";
        VectorResult result = metricQuery(url, promql, window.actualEnd());
        if (result.samples().isEmpty()) {
            return missing(name, unit, series, selector, semantics, "gauge", false,
                    AvailabilityReason.SERIES_NOT_FOUND, window.type(), windowScopedOrigin);
        }
        VectorSample sample = result.samples().getFirst();
        if (!window.contains(sample.timestamp())) {
            return missing(name, unit, series, selector, semantics, "gauge", false,
                    AvailabilityReason.SAMPLE_OUTSIDE_WINDOW, window.type(), windowScopedOrigin);
        }
        return available(name, sample.value(), unit, series, selector, semantics, "gauge", false, window.type(), windowScopedOrigin);
    }

    private MetricEvidence fromDerived(
            String url,
            MeasurementWindow window,
            String name,
            String unit,
            String series,
            String selector,
            MetricSemantics semantics,
            String method,
            String promql,
            Boolean windowScopedOrigin) {
        VectorResult result = metricQuery(url, promql, window.actualEnd());
        if (result.samples().isEmpty()) {
            return missing(name, unit, series, selector, semantics, method, true,
                    AvailabilityReason.SERIES_NOT_FOUND, window.type(), windowScopedOrigin);
        }
        VectorSample sample = result.samples().getFirst();
        if (sample.nan()) {
            return missing(name, unit, series, selector, semantics, method, true,
                    AvailabilityReason.NOT_A_NUMBER, window.type(), windowScopedOrigin);
        }
        return available(name, sample.value(), unit, series, selector, semantics, method, true, window.type(), windowScopedOrigin);
    }

    private static AvailabilityReason histogramReason(VectorResult primary, VectorResult count) {
        if (primary.samples().isEmpty() || count.samples().isEmpty()) {
            return AvailabilityReason.SERIES_NOT_FOUND;
        }
        boolean enough = primary.samples().stream().allMatch(sample -> sample.value() >= 2)
                && count.samples().stream().allMatch(sample -> sample.value() >= 2);
        if (!enough) {
            return AvailabilityReason.INSUFFICIENT_SAMPLES;
        }
        return null;
    }

    private VectorResult sampleCount(String url, String series, String range, Instant evaluationTime) {
        return Observation.createNotStarted("prometheus.sample-count", observationRegistry)
                .observe(() -> client.query(url, "count_over_time(" + series + range + ")", evaluationTime));
    }

    private VectorResult metricQuery(String url, String promql, Instant evaluationTime) {
        return Observation.createNotStarted("prometheus.metric-query", observationRegistry)
                .observe(() -> client.query(url, promql, evaluationTime));
    }

    private static String range(Duration duration) {
        return "[" + duration.toSeconds() + "s]";
    }

    private static String jobSelector(String job) {
        return "job=\"" + escape(job) + "\"";
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static MetricEvidence available(
            String name,
            double value,
            String unit,
            String series,
            String selector,
            MetricSemantics semantics,
            String method,
            boolean approximate,
            WindowType scope,
            Boolean windowScopedOrigin) {
        return new MetricEvidence(
                name, value, unit, "PROMETHEUS", series, selector, semantics, method, approximate,
                Availability.AVAILABLE, null, scope, windowScopedOrigin);
    }

    private static MetricEvidence missing(
            String name,
            String unit,
            String series,
            String selector,
            MetricSemantics semantics,
            String method,
            boolean approximate,
            AvailabilityReason reason,
            WindowType scope,
            Boolean windowScopedOrigin) {
        return MetricEvidence.unavailable(name, unit, series, selector, semantics, method, approximate, reason, scope, windowScopedOrigin);
    }
}
