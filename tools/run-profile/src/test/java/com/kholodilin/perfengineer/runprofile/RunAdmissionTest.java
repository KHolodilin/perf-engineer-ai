package com.kholodilin.perfengineer.runprofile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.kholodilin.perfengineer.runprofile.application.AcceptedRun;
import com.kholodilin.perfengineer.runprofile.application.StartRunUseCase;
import com.kholodilin.perfengineer.runprofile.application.WorkloadExecutionResult;
import com.kholodilin.perfengineer.runprofile.application.WorkloadRunner;
import com.kholodilin.perfengineer.runprofile.domain.StartRunCommand;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@Import(RunAdmissionTest.BlockingRunnerConfig.class)
@TestPropertySource(properties = {
        "spring.main.allow-bean-definition-overriding=true",
        "run-profile.queue-capacity=1",
        "run-profile.workloads.paused.reactor-root=${user.dir}",
        "run-profile.workloads.paused.module=load-tests",
        "run-profile.workloads.paused.simulation-class=com.example.PausedSimulation",
        "run-profile.workloads.paused.profile=steps",
        "run-profile.workloads.paused.enabled=false"
})
class RunAdmissionTest {

    private static final BlockingWorkloadRunner RUNNER = new BlockingWorkloadRunner();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private StartRunUseCase startRunUseCase;

    @AfterEach
    void releaseWorker() {
        RUNNER.unblock();
    }

    @Test
    void validationDoesNotConsumeTheKey() throws Exception {
        mockMvc.perform(post("/api/v1/runs").header("Idempotency-Key", "bad-then-good").contentType(MediaType.APPLICATION_JSON)
                        .content(body("ftp://127.0.0.1:8090", "outbox-create-order", 20)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.runId").doesNotExist());
        mockMvc.perform(post("/api/v1/runs").header("Idempotency-Key", "bad-then-good").contentType(MediaType.APPLICATION_JSON)
                        .content(body("http://127.0.0.1:8090", "outbox-create-order", 20)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("QUEUED"));
    }

    @Test
    void sameKeyReplaysAndDifferentBodyConflicts() throws Exception {
        String first = runId(mockMvc.perform(post("/api/v1/runs").header("Idempotency-Key", "same-key")
                .contentType(MediaType.APPLICATION_JSON).content(body("http://127.0.0.1:8090", "outbox-create-order", 20)))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString());
        String replay = runId(mockMvc.perform(post("/api/v1/runs").header("Idempotency-Key", "same-key")
                .contentType(MediaType.APPLICATION_JSON).content(body("http://127.0.0.1:8090", "outbox-create-order", 20)))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString());
        assertEquals(first, replay);
        mockMvc.perform(post("/api/v1/runs").header("Idempotency-Key", "same-key").contentType(MediaType.APPLICATION_JSON)
                        .content(body("http://127.0.0.1:8090", "outbox-create-order", 25)))
                .andExpect(status().isConflict());
        mockMvc.perform(get("/api/v1/runs/" + first)).andExpect(status().isOk()).andExpect(jsonPath("$.runId").value(first));
    }

    @Test
    void unknownAndDisabledWorkloadsDoNotStart() throws Exception {
        mockMvc.perform(post("/api/v1/runs").header("Idempotency-Key", "missing-workload").contentType(MediaType.APPLICATION_JSON)
                        .content(body("http://127.0.0.1:8090", "no-such-workload", 20)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("WORKLOAD_NOT_FOUND"));
        mockMvc.perform(post("/api/v1/runs").header("Idempotency-Key", "paused-workload").contentType(MediaType.APPLICATION_JSON)
                        .content(body("http://127.0.0.1:8090", "paused", 20)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("WORKLOAD_NOT_ALLOWED"));
    }

    @Test
    void queueOverflowLeavesTheKeyReusableAndOnlyOneRunExecutes() throws Exception {
        RUNNER.arm();
        String first = accepted("queue-a", 20);
        RUNNER.awaitStarted();
        String second = accepted("queue-b", 30);
        mockMvc.perform(post("/api/v1/runs").header("Idempotency-Key", "queue-c").contentType(MediaType.APPLICATION_JSON)
                        .content(body("http://127.0.0.1:8090", "outbox-create-order", 40)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("EXECUTION_REJECTED"))
                .andExpect(jsonPath("$.runId").doesNotExist());
        assertEquals(1, RUNNER.maxInFlight.get());
        RUNNER.unblock();
        String retried = accepted("queue-c", 40);
        assertNotEquals(first, retried);
        assertNotEquals(second, retried);
    }

    @Test
    void concurrentDuplicateAcceptsCreateOneRun() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            StartRunCommand command = ProfileAndWindowTest.command(20, 50, 70, 100, 30, 5, "http://127.0.0.1:8090");
            Future<AcceptedRun> left = pool.submit(() -> startRunUseCase.start("concurrent-key", command));
            Future<AcceptedRun> right = pool.submit(() -> startRunUseCase.start("concurrent-key", command));
            assertEquals(left.get(10, TimeUnit.SECONDS).runId(), right.get(10, TimeUnit.SECONDS).runId());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void traceContextReachesTheWorker() throws Exception {
        RUNNER.arm();
        mockMvc.perform(post("/api/v1/runs")
                        .header("Idempotency-Key", "traced")
                        .header("traceparent", "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("http://127.0.0.1:8090", "outbox-create-order", 20)))
                .andExpect(status().isAccepted());
        RUNNER.awaitStarted();
        assertEquals("traced", RUNNER.mdc.get().get("idempotencyKey"));
        assertEquals("order-service", RUNNER.mdc.get().get("targetId"));
        assertEquals("0af7651916cd43dd8448eb211c80319c", RUNNER.mdc.get().get("traceId"));
        assertTrue(RUNNER.mdc.get().get("spanId") != null && !RUNNER.mdc.get().get("spanId").isBlank());
    }

    @Test
    void unknownRunIsNotFound() throws Exception {
        mockMvc.perform(get("/api/v1/runs/missing")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RUN_NOT_FOUND"));
    }

    private String accepted(String key, int rps) throws Exception {
        String json = mockMvc.perform(post("/api/v1/runs").header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                        .content(body("http://127.0.0.1:8090", "outbox-create-order", rps)))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        return runId(json);
    }

    private static String runId(String json) {
        int start = json.indexOf("\"runId\":\"") + 9;
        return json.substring(start, json.indexOf('"', start));
    }

    private static String body(String baseUrl, String workloadId, int rps1) {
        return """
                {
                  "workload": {"id": "%s"},
                  "target": {"baseUrl": "%s"},
                  "profile": {"rps1": %d, "rps2": 50, "rps3": 70, "rps4": 100, "stageDurationSeconds": 30, "rampSeconds": 5},
                  "telemetry": {
                    "prometheusUrl": "http://127.0.0.1:9100",
                    "targets": [{"id": "order-service", "type": "SPRING_BOOT", "job": "order-service"}]
                  }
                }
                """.formatted(workloadId, baseUrl, rps1);
    }

    @TestConfiguration
    static class BlockingRunnerConfig {
        @Bean
        @Primary
        WorkloadRunner workloadRunner() {
            return RUNNER;
        }
    }

    static final class BlockingWorkloadRunner implements WorkloadRunner {
        private final AtomicInteger inFlight = new AtomicInteger();
        private final AtomicInteger maxInFlight = new AtomicInteger();
        private final AtomicReference<CountDownLatch> started = new AtomicReference<>(new CountDownLatch(1));
        private final AtomicReference<CountDownLatch> release = new AtomicReference<>(new CountDownLatch(1));
        private final AtomicReference<Map<String, String>> mdc = new AtomicReference<>();

        private volatile boolean block;

        void arm() {
            block = true;
            started.set(new CountDownLatch(1));
            release.set(new CountDownLatch(1));
            maxInFlight.set(0);
        }

        void unblock() {
            block = false;
            release.get().countDown();
        }

        void awaitStarted() throws InterruptedException {
            if (!started.get().await(10, TimeUnit.SECONDS)) {
                throw new AssertionError("worker did not start");
            }
        }

        @Override
        public WorkloadExecutionResult execute(
                com.kholodilin.perfengineer.runprofile.application.WorkloadDefinition workload,
                com.kholodilin.perfengineer.runprofile.domain.LoadProfile profile,
                com.kholodilin.perfengineer.runprofile.domain.Target target) {
            mdc.set(MDC.getCopyOfContextMap());
            if (!block) {
                return new WorkloadExecutionResult(Instant.now(), Instant.now(), 0, true, null, false);
            }
            int current = inFlight.incrementAndGet();
            maxInFlight.accumulateAndGet(current, Math::max);
            mdc.set(MDC.getCopyOfContextMap());
            started.get().countDown();
            try {
                release.get().await(20, TimeUnit.SECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            } finally {
                inFlight.decrementAndGet();
            }
            return new WorkloadExecutionResult(Instant.now(), Instant.now(), 0, true, null, false);
        }
    }
}
