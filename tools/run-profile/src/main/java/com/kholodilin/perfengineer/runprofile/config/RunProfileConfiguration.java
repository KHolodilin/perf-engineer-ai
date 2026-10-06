package com.kholodilin.perfengineer.runprofile.config;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.kholodilin.idempotency.IdempotencyService;
import com.kholodilin.idempotency.core.DefaultIdempotencyServiceBuilder;
import com.kholodilin.idempotency.jackson.CanonicalJsonFingerprintStrategy;
import com.kholodilin.idempotency.jackson.JacksonIdempotencySerializer;
import com.kholodilin.idempotency.spi.FingerprintStrategy;
import com.kholodilin.idempotency.spi.IdempotencySerializer;
import com.kholodilin.idempotency.spi.LocalCache;
import com.kholodilin.idempotency.spi.PersistenceStore;
import com.kholodilin.perfengineer.runprofile.application.AdmissionGate;
import com.kholodilin.perfengineer.runprofile.application.ProfileValidator;
import com.kholodilin.perfengineer.runprofile.application.RetentionPolicy;
import com.kholodilin.perfengineer.runprofile.application.RunExecutor;
import com.kholodilin.perfengineer.runprofile.application.RunJobLauncher;
import com.kholodilin.perfengineer.runprofile.application.TelemetryProvider;
import com.kholodilin.perfengineer.runprofile.application.WorkloadDefinition;
import com.kholodilin.perfengineer.runprofile.application.WorkloadRunner;
import com.kholodilin.perfengineer.runprofile.domain.RunRepository;
import com.kholodilin.perfengineer.runprofile.domain.WindowPlanner;
import com.kholodilin.perfengineer.runprofile.infrastructure.executor.ContextCopyingDecorator;
import com.kholodilin.perfengineer.runprofile.infrastructure.executor.ExecutorRunJobLauncher;
import com.kholodilin.perfengineer.runprofile.infrastructure.gatling.GatlingEvidenceParser;
import com.kholodilin.perfengineer.runprofile.infrastructure.gatling.GatlingWorkloadRunner;
import com.kholodilin.perfengineer.runprofile.infrastructure.gatling.MavenProcessRunner;
import com.kholodilin.perfengineer.runprofile.infrastructure.gatling.ReportDirectorySelector;
import com.kholodilin.perfengineer.runprofile.infrastructure.gatling.SimulationAnchorResolver;
import com.kholodilin.perfengineer.runprofile.infrastructure.idempotency.ExpiringCaffeineLocalCache;
import com.kholodilin.perfengineer.runprofile.infrastructure.idempotency.MemoryPersistenceStore;
import com.kholodilin.perfengineer.runprofile.infrastructure.memory.InMemoryRunRepository;
import com.kholodilin.perfengineer.runprofile.infrastructure.gatling.ProcessRunner;
import com.kholodilin.perfengineer.runprofile.infrastructure.prometheus.HttpPrometheusClient;
import com.kholodilin.perfengineer.runprofile.infrastructure.prometheus.MetricMeasurabilityPolicy;
import com.kholodilin.perfengineer.runprofile.infrastructure.prometheus.PrometheusClient;
import com.kholodilin.perfengineer.runprofile.infrastructure.prometheus.PrometheusTelemetryProvider;
import com.kholodilin.perfengineer.runprofile.infrastructure.prometheus.SpringBootEvidenceMapper;
import com.kholodilin.perfengineer.runprofile.infrastructure.workload.ConfigWorkloadRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
@EnableConfigurationProperties(RunProfileProperties.class)
public class RunProfileConfiguration {

    @Bean
    @ConditionalOnMissingBean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    JsonMapperBuilderCustomizer nonNullJson() {
        return builder -> builder.changeDefaultPropertyInclusion(inclusion ->
                inclusion.withValueInclusion(JsonInclude.Include.NON_NULL));
    }

    @Bean
    ProfileValidator profileValidator(RunProfileProperties properties) {
        RetentionPolicy.check(properties);
        return new ProfileValidator(properties.getMaxProfileDuration());
    }

    @Bean
    AdmissionGate admissionGate(RunProfileProperties properties) {
        return new AdmissionGate(1, properties.getQueueCapacity());
    }

    @Bean
    WindowPlanner windowPlanner() {
        return new WindowPlanner();
    }

    @Bean
    RunRepository runRepository(RunProfileProperties properties, Clock clock) {
        return new InMemoryRunRepository(properties.getRunRetention(), clock);
    }

    @Bean
    MemoryPersistenceStore memoryPersistenceStore(Clock clock) {
        return new MemoryPersistenceStore(clock);
    }

    @Bean
    LocalCache idempotencyLocalCache(RunProfileProperties properties, Clock clock) {
        return new ExpiringCaffeineLocalCache(properties.getIdempotencyRetention(), clock);
    }

    @Bean
    FingerprintStrategy fingerprintStrategy() {
        return new CanonicalJsonFingerprintStrategy("SHA-256");
    }

    @Bean
    IdempotencySerializer idempotencySerializer() {
        return new JacksonIdempotencySerializer();
    }

    @Bean
    IdempotencyService idempotencyService(
            PersistenceStore persistenceStore,
            LocalCache localCache,
            FingerprintStrategy fingerprintStrategy,
            IdempotencySerializer serializer,
            Clock clock,
            RunProfileProperties properties) {
        return new DefaultIdempotencyServiceBuilder(persistenceStore)
                .fingerprintStrategy(fingerprintStrategy)
                .serializer(serializer)
                .localCache(localCache)
                .clock(clock)
                .persistenceTtl(properties.getIdempotencyRetention())
                .requireActiveTransaction(false)
                .lookupBeforeAcquire(true)
                .build();
    }

    @Bean
    ConfigWorkloadRegistry workloadRegistry(RunProfileProperties properties) {
        Map<String, WorkloadDefinition> definitions = new LinkedHashMap<>();
        properties.getWorkloads().forEach((id, workload) -> definitions.put(id, new WorkloadDefinition(
                id,
                workload.getReactorRoot(),
                workload.getModule(),
                workload.getSimulationClass(),
                workload.getProfile(),
                workload.isEnabled(),
                workload.getMaxFailedPercent(),
                workload.getP95Ms())));
        return new ConfigWorkloadRegistry(definitions);
    }

    @Bean
    @ConditionalOnMissingBean(ProcessRunner.class)
    ProcessRunner processRunner() {
        return new MavenProcessRunner();
    }

    @Bean
    SimulationAnchorResolver simulationAnchorResolver(ObservationRegistry observationRegistry) {
        return new SimulationAnchorResolver(observationRegistry);
    }

    @Bean
    ReportDirectorySelector reportDirectorySelector(SimulationAnchorResolver resolver) {
        return new ReportDirectorySelector(resolver);
    }

    @Bean
    GatlingEvidenceParser gatlingEvidenceParser(ObservationRegistry observationRegistry) {
        return new GatlingEvidenceParser(observationRegistry);
    }

    @Bean
    @ConditionalOnMissingBean(WorkloadRunner.class)
    WorkloadRunner workloadRunner(
            ProcessRunner processRunner,
            ReportDirectorySelector selector,
            Clock clock,
            ObservationRegistry observationRegistry) {
        return new GatlingWorkloadRunner(processRunner, selector, clock, observationRegistry);
    }

    @Bean
    @ConditionalOnMissingBean
    PrometheusClient prometheusClient() {
        return new HttpPrometheusClient();
    }

    @Bean
    MetricMeasurabilityPolicy metricMeasurabilityPolicy() {
        return new MetricMeasurabilityPolicy();
    }

    @Bean
    SpringBootEvidenceMapper springBootEvidenceMapper(
            PrometheusClient prometheusClient,
            MetricMeasurabilityPolicy policy,
            ObservationRegistry observationRegistry) {
        return new SpringBootEvidenceMapper(prometheusClient, policy, observationRegistry);
    }

    @Bean
    @ConditionalOnMissingBean(TelemetryProvider.class)
    TelemetryProvider telemetryProvider(
            SpringBootEvidenceMapper mapper,
            RunProfileProperties properties,
            ObservationRegistry observationRegistry) {
        return new PrometheusTelemetryProvider(mapper, properties.getEffectiveScrapeInterval(), observationRegistry);
    }

    @Bean
    RunExecutor runExecutor(
            RunRepository repository,
            ConfigWorkloadRegistry registry,
            WorkloadRunner workloadRunner,
            GatlingEvidenceParser parser,
            SimulationAnchorResolver anchorResolver,
            WindowPlanner windowPlanner,
            TelemetryProvider telemetryProvider,
            AdmissionGate admissionGate,
            Clock clock) {
        return new RunExecutor(
                repository,
                registry,
                workloadRunner,
                parser,
                anchorResolver,
                windowPlanner,
                telemetryProvider,
                admissionGate,
                clock);
    }

    @Bean
    ContextCopyingDecorator contextCopyingDecorator() {
        return new ContextCopyingDecorator();
    }

    @Bean(name = "runProfileExecutor")
    ThreadPoolTaskExecutor runProfileExecutor(RunProfileProperties properties, ContextCopyingDecorator decorator) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(properties.getQueueCapacity());
        executor.setThreadNamePrefix("run-profile-");
        executor.setTaskDecorator(decorator);
        executor.setRejectedExecutionHandler(new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        return executor;
    }

    @Bean
    RunJobLauncher runJobLauncher(ThreadPoolTaskExecutor runProfileExecutor, RunExecutor runExecutor, ObservationRegistry observationRegistry) {
        return new ExecutorRunJobLauncher(runProfileExecutor, runExecutor, observationRegistry);
    }
}
