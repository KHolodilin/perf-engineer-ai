package com.kholodilin.perfengineer.runprofile.application;

import java.net.URI;
import java.time.Duration;

import com.kholodilin.perfengineer.runprofile.domain.LoadProfile;
import com.kholodilin.perfengineer.runprofile.domain.StartRunCommand;
import com.kholodilin.perfengineer.runprofile.domain.TelemetryTarget;

public final class ProfileValidator {

    private final Duration maxProfileDuration;

    public ProfileValidator(Duration maxProfileDuration) {
        this.maxProfileDuration = maxProfileDuration;
    }

    public void validate(StartRunCommand command) {
        requirePositive(command.rps1(), "rps1");
        requirePositive(command.rps2(), "rps2");
        requirePositive(command.rps3(), "rps3");
        requirePositive(command.rps4(), "rps4");
        if (command.rampSeconds() <= 0) {
            reject("rampSeconds must be positive");
        }
        if (command.stageDurationSeconds() <= 0) {
            reject("stageDurationSeconds must be positive");
        }
        requireHttpUrl(command.baseUrl(), "baseUrl");
        requireHttpUrl(command.prometheusUrl(), "prometheusUrl");
        if (command.targets().isEmpty()) {
            reject("telemetry target is required");
        }
        for (TelemetryTarget target : command.targets()) {
            if (target.id() == null || target.id().isBlank() || target.job() == null || target.job().isBlank()) {
                reject("telemetry target id and job are required");
            }
            if (!"SPRING_BOOT".equals(target.type())) {
                reject("telemetry target type must be SPRING_BOOT");
            }
        }
        LoadProfile profile = command.profile();
        if (profile.profileDurationSeconds() > maxProfileDuration.getSeconds()) {
            reject("Profile duration exceeds maxProfileDurationSeconds");
        }
    }

    private static void requirePositive(double value, String name) {
        if (Double.isNaN(value) || Double.isInfinite(value) || value <= 0) {
            reject(name + " must be positive");
        }
    }

    private static void requireHttpUrl(String value, String name) {
        if (value == null || value.isBlank()) {
            reject(name + " must be an absolute http or https URL");
        }
        URI uri;
        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException ex) {
            reject(name + " must be an absolute http or https URL");
            return;
        }
        String scheme = uri.getScheme();
        if (!uri.isAbsolute() || uri.getHost() == null || scheme == null
                || (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))) {
            reject(name + " must be an absolute http or https URL");
        }
    }

    private static void reject(String message) {
        throw new RequestRejectedException("INVALID_REQUEST", message, 400);
    }
}
