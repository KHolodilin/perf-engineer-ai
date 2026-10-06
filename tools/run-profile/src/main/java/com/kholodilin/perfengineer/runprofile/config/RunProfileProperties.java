package com.kholodilin.perfengineer.runprofile.config;

import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@ConfigurationProperties(prefix = "run-profile")
public class RunProfileProperties {

    private Duration maxProfileDuration;
    private Duration startupAllowance;
    private Duration collectionAllowance;
    private Duration executionTimeout;
    private Duration clientRetryWindow;
    private Duration runRetention;
    private Duration idempotencyRetention;
    private int queueCapacity;
    private Duration effectiveScrapeInterval;
    private Map<String, Workload> workloads = new LinkedHashMap<>();

    @Getter
    @Setter
    public static class Workload {
        private Path reactorRoot;
        private String module;
        private String simulationClass;
        private String profile;
        private boolean enabled = true;
        private double maxFailedPercent = 0.5;
        private int p95Ms = 200;
    }
}
