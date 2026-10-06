package com.kholodilin.perfengineer.runprofile.domain;

public record MetricEvidence(
        String name,
        Double value,
        String unit,
        String source,
        String series,
        String selector,
        MetricSemantics semantics,
        String method,
        boolean approximate,
        Availability availability,
        AvailabilityReason reason,
        WindowType temporalScope,
        Boolean windowScopedOrigin) {

    public static MetricEvidence unavailable(
            String name,
            String unit,
            String series,
            String selector,
            MetricSemantics semantics,
            String method,
            boolean approximate,
            AvailabilityReason reason,
            WindowType temporalScope,
            Boolean windowScopedOrigin) {
        return new MetricEvidence(
                name,
                null,
                unit,
                "PROMETHEUS",
                series,
                selector,
                semantics,
                method,
                approximate,
                Availability.UNAVAILABLE,
                reason,
                temporalScope,
                windowScopedOrigin);
    }
}
