package com.kholodilin.perfengineer.runprofile.application;

import java.nio.file.Path;
import java.util.Locale;

public record WorkloadDefinition(
        String id,
        Path reactorRoot,
        String module,
        String simulationClass,
        String profile,
        boolean enabled,
        double maxFailedPercent,
        int p95Ms) {

    public String reportNamePrefix() {
        int dot = simulationClass.lastIndexOf('.');
        String simple = dot < 0 ? simulationClass : simulationClass.substring(dot + 1);
        return simple.toLowerCase(Locale.ROOT) + "-";
    }
}
