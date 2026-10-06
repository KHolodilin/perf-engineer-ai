package com.kholodilin.perfengineer.runprofile.application;

import java.nio.file.Path;
import java.time.Instant;

import com.kholodilin.perfengineer.runprofile.domain.LoadProfile;
import com.kholodilin.perfengineer.runprofile.domain.Target;

public interface WorkloadRunner {

    WorkloadExecutionResult execute(WorkloadDefinition workload, LoadProfile profile, Target target);
}
