package com.kholodilin.perfengineer.runprofile.infrastructure.gatling;

import java.nio.file.Path;
import java.util.List;

import com.kholodilin.perfengineer.runprofile.application.ProcessOutcome;

public interface ProcessRunner {

    ProcessOutcome run(List<String> command, Path workingDirectory);
}
