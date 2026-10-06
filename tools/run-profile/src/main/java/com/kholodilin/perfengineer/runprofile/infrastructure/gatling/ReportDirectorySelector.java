package com.kholodilin.perfengineer.runprofile.infrastructure.gatling;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.Optional;
import java.util.stream.Stream;

public final class ReportDirectorySelector {

    private final SimulationAnchorResolver anchorResolver;

    public ReportDirectorySelector(SimulationAnchorResolver anchorResolver) {
        this.anchorResolver = anchorResolver;
    }

    public Optional<Path> selectAnchored(Path gatlingDirectory, String namePrefix, Instant mavenProcessStartedAt) {
        return list(gatlingDirectory, namePrefix)
                .filter(path -> anchorResolver.resolve(path)
                        .filter(timestamp -> !timestamp.isBefore(mavenProcessStartedAt))
                        .isPresent())
                .min(Comparator.comparing(path -> anchorResolver.resolve(path).orElseThrow()));
    }

    public Optional<Path> selectUnreadable(Path gatlingDirectory, String namePrefix, Instant mavenProcessStartedAt) {
        return list(gatlingDirectory, namePrefix)
                .filter(path -> anchorResolver.resolve(path).isEmpty())
                .filter(path -> lastModified(path).compareTo(mavenProcessStartedAt) >= 0)
                .min(Comparator.comparing(this::lastModified));
    }

    private Stream<Path> list(Path gatlingDirectory, String namePrefix) {
        if (gatlingDirectory == null || !Files.isDirectory(gatlingDirectory)) {
            return Stream.empty();
        }
        try {
            return Files.list(gatlingDirectory)
                    .filter(Files::isDirectory)
                    .filter(path -> path.getFileName().toString().startsWith(namePrefix))
                    .toList()
                    .stream();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private Instant lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toInstant();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
