package com.kholodilin.perfengineer.runprofile.infrastructure.prometheus;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

public final class HttpPrometheusClient implements PrometheusClient {

    private final HttpClient httpClient;
    private final JsonMapper mapper = JsonMapper.builder().build();

    public HttpPrometheusClient() {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
    }

    HttpPrometheusClient(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    @Override
    public VectorResult query(String prometheusUrl, String promql, Instant evaluationTime) {
        if (promql.contains(" @ ")) {
            throw new IllegalArgumentException("Prometheus queries use the time parameter");
        }
        String base = prometheusUrl.endsWith("/") ? prometheusUrl.substring(0, prometheusUrl.length() - 1) : prometheusUrl;
        String uri = base + "/api/v1/query?query=" + URLEncoder.encode(promql, StandardCharsets.UTF_8)
                + "&time=" + epochSeconds(evaluationTime);
        HttpRequest request = HttpRequest.newBuilder(URI.create(uri))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException ex) {
            throw new PrometheusUnavailableException("Prometheus query failed", ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new PrometheusUnavailableException("Prometheus query interrupted", ex);
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new PrometheusUnavailableException("Prometheus returned HTTP " + response.statusCode());
        }
        JsonNode root = mapper.readTree(response.body());
        if (!"success".equals(root.path("status").asString())) {
            throw new PrometheusUnavailableException("Prometheus query was not successful");
        }
        JsonNode result = root.path("data").path("result");
        List<VectorSample> samples = new ArrayList<>();
        if (result.isArray()) {
            for (JsonNode item : result) {
                JsonNode value = item.path("value");
                if (!value.isArray() || value.size() < 2) {
                    continue;
                }
                Map<String, String> labels = new LinkedHashMap<>();
                JsonNode metric = item.path("metric");
                if (metric.isObject()) {
                    metric.properties().forEach(entry -> labels.put(entry.getKey(), entry.getValue().asString()));
                }
                double epoch = value.get(0).asDouble();
                String raw = value.get(1).asString();
                double numeric = "NaN".equals(raw) ? Double.NaN : Double.parseDouble(raw);
                long millis = Math.round(epoch * 1000d);
                samples.add(new VectorSample(labels, Instant.ofEpochMilli(millis), numeric));
            }
        }
        return new VectorResult(samples);
    }

    private static String epochSeconds(Instant time) {
        long seconds = time.getEpochSecond();
        int nanos = time.getNano();
        if (nanos == 0) {
            return Long.toString(seconds);
        }
        String fraction = String.format(Locale.ROOT, "%09d", nanos).replaceAll("0+$", "");
        return seconds + "." + fraction;
    }
}
