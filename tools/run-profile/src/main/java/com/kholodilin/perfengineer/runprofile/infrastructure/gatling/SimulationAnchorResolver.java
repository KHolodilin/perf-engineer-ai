package com.kholodilin.perfengineer.runprofile.infrastructure.gatling;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.nio.file.Path;
import java.util.Optional;

import com.kholodilin.perfengineer.runprofile.application.AnchorResolver;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;

public final class SimulationAnchorResolver implements AnchorResolver {

    private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");

    private final ObservationRegistry observationRegistry;

    public SimulationAnchorResolver(ObservationRegistry observationRegistry) {
        this.observationRegistry = observationRegistry;
    }

    @Override
    public Optional<Instant> resolve(Path reportDirectory) {
        return Observation.createNotStarted("simulation.anchor.resolve", observationRegistry)
                .observe(() -> parse(reportDirectory));
    }

    private Optional<Instant> parse(Path reportDirectory) {
        if (reportDirectory == null || reportDirectory.getFileName() == null) {
            return Optional.empty();
        }
        String name = reportDirectory.getFileName().toString();
        int dash = name.lastIndexOf('-');
        if (dash < 0 || dash == name.length() - 1) {
            return Optional.empty();
        }
        String digits = name.substring(dash + 1);
        if (digits.length() != 17 || !digits.chars().allMatch(Character::isDigit)) {
            return Optional.empty();
        }
        try {
            LocalDateTime local = LocalDateTime.parse(digits, FORMAT);
            return Optional.of(local.toInstant(ZoneOffset.UTC));
        } catch (DateTimeParseException ex) {
            return Optional.empty();
        }
    }
}
