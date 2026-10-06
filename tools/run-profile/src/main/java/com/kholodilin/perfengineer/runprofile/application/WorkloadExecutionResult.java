package com.kholodilin.perfengineer.runprofile.application;

import java.nio.file.Path;
import java.time.Instant;

public record WorkloadExecutionResult(
        Instant mavenProcessStartedAt,
        Instant mavenProcessFinishedAt,
        int exitCode,
        boolean started,
        Path reportDirectory,
        boolean anchorReadable) {
}
