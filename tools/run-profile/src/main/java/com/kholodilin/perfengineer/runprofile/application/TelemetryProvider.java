package com.kholodilin.perfengineer.runprofile.application;

import java.util.List;

import com.kholodilin.perfengineer.runprofile.domain.MeasurementWindow;
import com.kholodilin.perfengineer.runprofile.domain.MetricEvidence;
import com.kholodilin.perfengineer.runprofile.domain.TelemetryTarget;

public interface TelemetryProvider {

    TelemetryEvidence collect(String prometheusUrl, TelemetryTarget target, MeasurementWindow window);

    List<MetricEvidence> unavailable(MeasurementWindow window);
}
