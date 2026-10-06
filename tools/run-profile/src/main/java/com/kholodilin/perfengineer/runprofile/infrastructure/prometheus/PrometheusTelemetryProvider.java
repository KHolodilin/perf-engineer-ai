package com.kholodilin.perfengineer.runprofile.infrastructure.prometheus;

import java.time.Duration;
import java.util.List;

import com.kholodilin.perfengineer.runprofile.application.TelemetryEvidence;
import com.kholodilin.perfengineer.runprofile.application.TelemetryProvider;
import com.kholodilin.perfengineer.runprofile.domain.AvailabilityReason;
import com.kholodilin.perfengineer.runprofile.domain.MeasurementWindow;
import com.kholodilin.perfengineer.runprofile.domain.MetricEvidence;
import com.kholodilin.perfengineer.runprofile.domain.TelemetryTarget;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;

public final class PrometheusTelemetryProvider implements TelemetryProvider {

    private final SpringBootEvidenceMapper mapper;
    private final Duration scrapeInterval;
    private final ObservationRegistry observationRegistry;

    public PrometheusTelemetryProvider(
            SpringBootEvidenceMapper mapper,
            Duration scrapeInterval,
            ObservationRegistry observationRegistry) {
        this.mapper = mapper;
        this.scrapeInterval = scrapeInterval;
        this.observationRegistry = observationRegistry;
    }

    @Override
    public TelemetryEvidence collect(String prometheusUrl, TelemetryTarget target, MeasurementWindow window) {
        return Observation.createNotStarted("telemetry.collect", observationRegistry).observe(() -> {
            TelemetryEvidence evidence = mapper.collect(prometheusUrl, target, window, scrapeInterval);
            if (!evidence.prometheusAvailable()) {
                return new TelemetryEvidence(mapper.unavailable(window, AvailabilityReason.PROMETHEUS_UNAVAILABLE), false);
            }
            return evidence;
        });
    }

    @Override
    public List<MetricEvidence> unavailable(MeasurementWindow window) {
        return mapper.unavailable(window, AvailabilityReason.PROMETHEUS_UNAVAILABLE);
    }
}
