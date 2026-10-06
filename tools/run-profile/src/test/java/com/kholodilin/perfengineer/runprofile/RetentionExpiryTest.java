package com.kholodilin.perfengineer.runprofile;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import com.kholodilin.perfengineer.runprofile.application.WorkloadExecutionResult;
import com.kholodilin.perfengineer.runprofile.application.WorkloadRunner;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@Import(RetentionExpiryTest.ShortRetentionConfig.class)
@TestPropertySource(properties = {
        "spring.main.allow-bean-definition-overriding=true",
        "run-profile.max-profile-duration=60s",
        "run-profile.startup-allowance=1s",
        "run-profile.collection-allowance=1s",
        "run-profile.execution-timeout=62s",
        "run-profile.client-retry-window=1s",
        "run-profile.run-retention=64s",
        "run-profile.idempotency-retention=64s",
        "run-profile.queue-capacity=1"
})
class RetentionExpiryTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MutableClock clock;

    @Test
    void expiredRunIsNotFoundAndTheKeyCanBeReused() throws Exception {
        String first = runId(mockMvc.perform(post("/api/v1/runs").header("Idempotency-Key", "retain")
                        .contentType(MediaType.APPLICATION_JSON).content(request()))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString());
        mockMvc.perform(get("/api/v1/runs/" + first)).andExpect(status().isOk());
        clock.advance(Duration.ofSeconds(64));
        mockMvc.perform(get("/api/v1/runs/" + first)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RUN_NOT_FOUND"));
        String second = runId(mockMvc.perform(post("/api/v1/runs").header("Idempotency-Key", "retain")
                        .contentType(MediaType.APPLICATION_JSON).content(request()))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString());
        assertNotEquals(first, second);
    }

    private static String request() {
        return """
                {
                  "workload": {"id": "outbox-create-order"},
                  "target": {"baseUrl": "http://127.0.0.1:8090"},
                  "profile": {"rps1": 20, "rps2": 50, "rps3": 70, "rps4": 100, "stageDurationSeconds": 1, "rampSeconds": 1},
                  "telemetry": {
                    "prometheusUrl": "http://127.0.0.1:9100",
                    "targets": [{"id": "order-service", "type": "SPRING_BOOT", "job": "order-service"}]
                  }
                }
                """;
    }

    private static String runId(String json) {
        int start = json.indexOf("\"runId\":\"") + 9;
        return json.substring(start, json.indexOf('"', start));
    }

    static final class MutableClock extends Clock {
        private final AtomicReference<Instant> instant = new AtomicReference<>(Instant.parse("2026-10-06T09:00:00Z"));

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant.get();
        }

        void advance(Duration duration) {
            instant.updateAndGet(current -> current.plus(duration));
        }
    }

    @TestConfiguration
    static class ShortRetentionConfig {
        @Bean
        @Primary
        MutableClock clock() {
            return new MutableClock();
        }

        @Bean
        @Primary
        WorkloadRunner workloadRunner() {
            return (workload, profile, target) ->
                    new WorkloadExecutionResult(Instant.parse("2026-10-06T09:00:01Z"), Instant.parse("2026-10-06T09:00:02Z"), 0, true, null, false);
        }
    }
}
