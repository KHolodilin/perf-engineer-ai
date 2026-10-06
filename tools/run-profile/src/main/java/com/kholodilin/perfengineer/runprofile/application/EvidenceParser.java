package com.kholodilin.perfengineer.runprofile.application;

import java.nio.file.Path;
import java.util.Optional;

public interface EvidenceParser {

    Optional<GatlingReport> parse(Path reportDirectory, WorkloadDefinition workload);
}
