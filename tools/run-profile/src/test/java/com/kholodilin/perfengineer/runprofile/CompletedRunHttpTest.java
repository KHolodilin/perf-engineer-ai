package com.kholodilin.perfengineer.runprofile;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import com.kholodilin.perfengineer.runprofile.application.WorkloadExecutionResult;
import com.kholodilin.perfengineer.runprofile.application.WorkloadRunner;
import com.kholodilin.perfengineer.runprofile.infrastructure.prometheus.PrometheusClient;
import com.kholodilin.perfengineer.runprofile.infrastructure.prometheus.VectorResult;
import com.kholodilin.perfengineer.runprofile.infrastructure.prometheus.VectorSample;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "spring.main.allow-bean-definition-overriding=true")
@AutoConfigureMockMvc
@Import(CompletedRunHttpTest.EvidenceConfig.class)
class CompletedRunHttpTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void fullGetContainsGatlingTotalsAndAllEightWindows() throws Exception {
        String body = mockMvc.perform(post("/api/v1/runs").header("Idempotency-Key", "full-run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request()))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        String runId = body.substring(body.indexOf("\"runId\":\"") + 9, body.indexOf("\"", body.indexOf("\"runId\":\"") + 9));
        String completed = "";
        for (int attempt = 0; attempt < 50; attempt++) {
            completed = mockMvc.perform(get("/api/v1/runs/" + runId)).andReturn().getResponse().getContentAsString();
            if (completed.contains("\"COMPLETED\"")) {
                break;
            }
            Thread.sleep(50);
        }
        mockMvc.perform(get("/api/v1/runs/" + runId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.evidenceStatus").value("FULL"))
                .andExpect(jsonPath("$.gatling.requests").value(12))
                .andExpect(jsonPath("$.gatling.ok").value(12))
                .andExpect(jsonPath("$.gatling.ko").value(0))
                .andExpect(jsonPath("$.gatlingAssertions.status").value("FAILED"))
                .andExpect(jsonPath("$.stages.length()").value(4))
                .andExpect(jsonPath("$.stages[0].windows.length()").value(2))
                .andExpect(jsonPath("$.stages[3].windows[1].type").value("HOLD"))
                .andExpect(jsonPath("$.stages[0].windows[1].metrics[?(@.name == 'httpLatencyP99')].series")
                        .value("http_server_requests_seconds_bucket"))
                .andExpect(jsonPath("$.traceId").doesNotExist())
                .andExpect(jsonPath("$.spanId").doesNotExist());
    }

    private static String request() {
        return """
                {
                  "workload": {"id": "outbox-create-order"},
                  "target": {"baseUrl": "http://127.0.0.1:8090"},
                  "profile": {"rps1": 20, "rps2": 50, "rps3": 70, "rps4": 100, "stageDurationSeconds": 30, "rampSeconds": 5},
                  "telemetry": {
                    "prometheusUrl": "http://127.0.0.1:9100",
                    "targets": [{"id": "order-service", "type": "SPRING_BOOT", "job": "order-service"}]
                  }
                }
                """;
    }

    @TestConfiguration
    static class EvidenceConfig {
        @Bean
        @Primary
        WorkloadRunner workloadRunner() {
            return (workload, profile, target) -> {
                try {
                    Path directory = Files.createTempDirectory("run-profile-" + UUID.randomUUID())
                            .resolve("createordersimulation-20261006092234073");
                    Files.createDirectories(directory);
                    Files.writeString(directory.resolve("index.html"), """
                            <table id="container_assertions"><td>KO</td></table>
                            <tr id="ROOT">
                              <td class="value total col-2">12</td>
                              <td class="value ok col-3">12</td>
                              <td class="value ko col-4">0</td>
                            </tr>
                            """);
                    return new WorkloadExecutionResult(Instant.now(), Instant.now(), 1, true, directory, true);
                } catch (java.io.IOException ex) {
                    throw new java.io.UncheckedIOException(ex);
                }
            };
        }

        @Bean
        @Primary
        PrometheusClient prometheusClient() {
            return (url, promql, time) -> new VectorResult(java.util.List.of(new VectorSample(Map.of(), time, 2)));
        }
    }
}
