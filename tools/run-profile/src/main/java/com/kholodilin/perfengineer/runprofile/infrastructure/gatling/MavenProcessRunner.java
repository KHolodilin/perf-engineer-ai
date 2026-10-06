package com.kholodilin.perfengineer.runprofile.infrastructure.gatling;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

import com.kholodilin.perfengineer.runprofile.application.ProcessOutcome;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public final class MavenProcessRunner implements ProcessRunner {

    public ProcessOutcome run(List<String> command, Path workingDirectory) {
        ProcessBuilder builder = new ProcessBuilder(launch(command));
        builder.directory(workingDirectory.toFile());
        builder.redirectErrorStream(true);
        try {
            Process process = builder.start();
            String tail = drain(process);
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                log.info("Maven process finished with exit code {}\n{}", exitCode, tail);
            }
            return new ProcessOutcome(exitCode, true);
        } catch (IOException ex) {
            log.info("Maven process failed to start");
            return new ProcessOutcome(-1, false);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return new ProcessOutcome(-1, true);
        }
    }

    static List<String> launch(List<String> command) {
        if (command.isEmpty() || !"mvn".equals(command.get(0)) || !windows()) {
            return command;
        }
        List<String> launched = new ArrayList<>();
        launched.add("cmd.exe");
        launched.add("/c");
        launched.add("mvn.cmd");
        launched.addAll(command.subList(1, command.size()));
        return launched;
    }

    private static boolean windows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows");
    }

    private static String drain(Process process) throws IOException {
        Deque<String> tail = new ArrayDeque<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                tail.addLast(line);
                if (tail.size() > 80) {
                    tail.removeFirst();
                }
            }
        }
        return String.join("\n", tail);
    }
}
