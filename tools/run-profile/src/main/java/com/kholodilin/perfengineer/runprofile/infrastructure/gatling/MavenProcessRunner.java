package com.kholodilin.perfengineer.runprofile.infrastructure.gatling;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import com.kholodilin.perfengineer.runprofile.application.ProcessOutcome;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public final class MavenProcessRunner implements ProcessRunner {

    public ProcessOutcome run(List<String> command, Path workingDirectory) {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(workingDirectory.toFile());
        builder.redirectErrorStream(true);
        try {
            Process process = builder.start();
            process.getInputStream().transferTo(java.io.OutputStream.nullOutputStream());
            int exitCode = process.waitFor();
            return new ProcessOutcome(exitCode, true);
        } catch (IOException ex) {
            log.info("Maven process failed to start");
            return new ProcessOutcome(-1, false);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return new ProcessOutcome(-1, true);
        }
    }
}
