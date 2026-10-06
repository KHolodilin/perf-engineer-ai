package com.kholodilin.perfengineer.runprofile.infrastructure.prometheus;

import java.time.Instant;

public interface PrometheusClient {

    VectorResult query(String prometheusUrl, String promql, Instant evaluationTime);
}
