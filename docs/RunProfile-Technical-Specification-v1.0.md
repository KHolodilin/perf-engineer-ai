# RunProfile — Technical Specification v1.0

**Project:** PerfEngineerAI
**Service:** `run-profile`
**Repository location:** `perf-engineer-ai/tools/run-profile/`
**Java:** 21
**Framework:** Spring Boot
**Build:** Maven
**Base package:** `com.kholodilin.perfengineer.runprofile`
**Maven coordinates:** `com.kholodilin.perfengineer:run-profile`

This version is the implementation contract. It keeps the v0.10 measurement model and closes the remaining contradictions: boundary ownership, availability reasons, acceptance series names, Gatling report selection, and the completed-run example.

## 1. Purpose

`RunProfile` executes a known performance workload, builds the simulation timeline, collects workload and server-side telemetry, and returns compact machine-readable evidence.

```text
Performance question
        ↓
       AI
        ↓ chooses experiment
   RunProfile
        ↓
known Gatling workload
        +
Prometheus telemetry
        ↓
source-aware evidence
        ↓
       AI
```

> **AI reasons. RunProfile executes and measures.**

> **RunProfile does not guarantee that a performance experiment succeeds. It preserves every reliably observed measurement and returns it with explicit source, temporal scope, availability, measurement semantics, and known limitations.**

RunProfile does not decide what the evidence means.

## 2. Scope

v1.0 is tied to the existing acceptance workload.

It supports:

- Gatling 3.15 and the existing `CreateOrderSimulation`
- `profile=steps`
- exactly four RPS levels, `rps1`–`rps4`
- one common `stageDurationSeconds`
- one common `rampSeconds` before every hold
- explicit `baseUrl`, reachable from the Maven process
- execution from the Maven reactor root
- explicit Gatling simulation FQCN
- separate `RAMP` and `HOLD` windows
- simulation-start anchoring from the report directory of this run, parsed as UTC
- half-open windows `(actualStart, actualEnd]`
- Gatling run-level evidence
- Prometheus window-level evidence where measurable
- pre-query and post-query raw-sample checks
- gauge sample timestamps checked against the same half-open window
- the normative Spring Boot series package below
- one execution worker and a bounded queue
- in-memory run state
- `spring-boot-idempotency-starter` with Caffeine
- partial evidence
- structured English logs
- OpenTelemetry with W3C Trace Context

The workload may enter one service while telemetry observes several components. RunProfile does not infer topology. Targets are requested explicitly.

## 3. Non-goals

v1.0 does not:

- generate Gatling scenarios or rewrite `CreateOrderSimulation`
- expose an arbitrary `{rps, duration}` stage list
- claim per-window client latency from the Gatling 3.15 binary log
- manufacture Prometheus values from too few raw samples
- treat instant-query cardinality as a sample count
- widen a range vector past the measurement window
- evaluate a historical window at collection-time `now`
- treat a gauge scrape inside the window as proof that the underlying spike started there
- publish a gauge sample whose timestamp lies outside the window
- turn an unreachable application or a failed Gatling assertion into a RunProfile failure when a usable report exists
- decide SLO pass/fail, choose the next RPS, or diagnose a bottleneck
- modify the target system
- contain an LLM
- survive process restart
- run as multiple instances
- add PostgreSQL, Kafka, leases, or an outbox for RunProfile itself

## 4. REST API

Base path: `/api/v1`. Runs are asynchronous.

### 4.1 Start a run

```http
POST /api/v1/runs
Idempotency-Key: <required>
Content-Type: application/json
traceparent: <optional>
tracestate: <optional>
```

```json
{
  "workload": {
    "id": "outbox-create-order"
  },
  "target": {
    "baseUrl": "http://127.0.0.1:8090"
  },
  "profile": {
    "rps1": 20,
    "rps2": 50,
    "rps3": 70,
    "rps4": 100,
    "stageDurationSeconds": 300,
    "rampSeconds": 60
  },
  "telemetry": {
    "prometheusUrl": "http://127.0.0.1:9100",
    "targets": [
      {
        "id": "order-service",
        "type": "SPRING_BOOT",
        "job": "order-service"
      }
    ]
  }
}
```

`baseUrl` is the address the Gatling process can open. Maven runs on the RunProfile host, not inside the application compose network. On the baseline host that address was `http://127.0.0.1:8090`. A compose DNS name such as `order-service` is valid only when that process can resolve it.

Accepted:

```http
202 Accepted
```

```json
{
  "runId": "0199...",
  "status": "QUEUED"
}
```

Rules:

- `Idempotency-Key` is mandatory.
- `workload.id` selects a server-side allowlist entry. The caller cannot send a filesystem path, Maven module, or simulation class.
- `baseUrl`, `stageDurationSeconds`, and `rampSeconds` are explicit.
- Validation happens before admission.
- `traceId` and `spanId` are not business DTO fields.

### 4.2 Validation failure

Invalid body or a profile that fails the rules in section 5:

```http
400 Bad Request
```

```json
{
  "code": "INVALID_REQUEST",
  "message": "Profile duration exceeds maxProfileDurationSeconds"
}
```

No `runId`, no repository entry, and the idempotency key is not consumed.

Unknown `workload.id` uses `WORKLOAD_NOT_FOUND`. A known id that is disabled uses `WORKLOAD_NOT_ALLOWED`. Both are `400` and do not consume the key.

### 4.3 Idempotency conflict

Same `Idempotency-Key` and a different request body:

```http
409 Conflict
```

The existing run is unchanged.

### 4.4 Queue full

```http
503 Service Unavailable
```

```json
{
  "code": "EXECUTION_REJECTED",
  "message": "Run execution queue is full"
}
```

No `runId`, no repository entry, no committed idempotency result. The same key may be retried when capacity exists.

Admission must not be `remainingCapacity() → create run → submit()`. A race must not leave a run or an idempotency record for a task that was not accepted.

### 4.5 Get a run

```http
GET /api/v1/runs/{runId}
```

While running, the body includes `status`, `createdAt`, and `startedAt` when execution has started.

Completed shape:

```json
{
  "runId": "0199...",
  "status": "COMPLETED",
  "evidenceStatus": "FULL",
  "createdAt": "2026-10-06T09:22:30Z",
  "startedAt": "2026-10-06T09:22:31Z",
  "simulationStartedAt": "2026-10-06T09:22:34.073Z",
  "finishedAt": "2026-10-06T09:46:35Z",
  "gatlingAssertions": {
    "status": "FAILED",
    "maxFailedPercent": 0.5,
    "p95Ms": 200
  },
  "gatling": {
    "requests": 14925,
    "ok": 14318,
    "ko": 607
  },
  "stages": [
    {
      "stage": 1,
      "requestedRps": 20,
      "windows": [
        {
          "type": "RAMP",
          "actualStart": "2026-10-06T09:22:34.073Z",
          "actualEnd": "2026-10-06T09:23:34.073Z",
          "metrics": []
        },
        {
          "type": "HOLD",
          "actualStart": "2026-10-06T09:23:34.073Z",
          "actualEnd": "2026-10-06T09:28:34.073Z",
          "metrics": [
            {
              "name": "httpLatencyP99",
              "value": 0.175,
              "unit": "seconds",
              "source": "PROMETHEUS",
              "series": "http_server_requests_seconds",
              "selector": "status=\"201\"",
              "semantics": "HISTOGRAM_QUANTILE",
              "method": "histogram_quantile(rate())",
              "approximate": true,
              "availability": "AVAILABLE",
              "temporalScope": "HOLD"
            }
          ]
        }
      ]
    }
  ]
}
```

`evidenceStatus=FULL` means run-level Gatling evidence and aligned windows are present. Empty `gatling` or empty `stages` is not `FULL`. The body above is shortened to one stage and one metric. A full four-level run contains all eight `RAMP` and `HOLD` windows.

`COMPLETED` means usable experiment evidence exists. It does not mean assertions passed, the target was reachable, or every metric is available. A completed run whose Prometheus data is missing or whose anchor could not be read is `evidenceStatus=PARTIAL` and still carries whatever Gatling evidence was recovered.

Repeated metadata may be factored into a shared descriptor. Each value still has to answer: what was measured, where it came from, which window it belongs to, whether it is available, how it was aggregated, and whether it is approximate.

After retention expiry, `GET` returns `404` with `RUN_NOT_FOUND`.

## 5. Profile

The public profile matches `CreateOrderSimulation` in `steps` mode.

```json
{
  "rps1": 20,
  "rps2": 50,
  "rps3": 70,
  "rps4": 100,
  "stageDurationSeconds": 300,
  "rampSeconds": 60
}
```

Physical injection:

```text
RAMP → HOLD rps1
RAMP → HOLD rps2
RAMP → HOLD rps3
RAMP → HOLD rps4
```

Validation before admission:

```text
rps1 > 0
rps2 > 0
rps3 > 0
rps4 > 0
rampSeconds > 0
stageDurationSeconds > 0
baseUrl is an absolute http or https URL

profileDurationSeconds = 4 × (rampSeconds + stageDurationSeconds)
profileDurationSeconds <= maxProfileDurationSeconds
```

Execution timeout is separate from measured load duration:

```text
executionTimeout >= maxProfileDuration + startupAllowance + collectionAllowance
```

A profile that fails these rules is `400 INVALID_REQUEST`.

## 6. Allowlisted workload

```yaml
run-profile:
  workloads:
    outbox-create-order:
      reactor-root: /workloads/spring-transactional-outbox-kafka
      module: load-tests
      simulation-class: com.kholodilin.outbox.loadtests.CreateOrderSimulation
      profile: steps
```

The mounted reactor must resolve the parent `1.4.0-SNAPSHOT` build. The host that runs RunProfile has JDK 21, Maven, and that reactor. The caller cannot override reactor root, module, simulation class, or profile mode.

## 7. Maven invocation

From the allowlisted reactor root, arguments are passed as separate process arguments, not concatenated into a shell string:

```text
mvn -pl load-tests gatling:test
  -Dgatling.simulationClass=com.kholodilin.outbox.loadtests.CreateOrderSimulation
  -Dprofile=steps
  -DbaseUrl=<request.target.baseUrl>
  -Drps1=<profile.rps1>
  -Drps2=<profile.rps2>
  -Drps3=<profile.rps3>
  -Drps4=<profile.rps4>
  -DstageDurationSeconds=<profile.stageDurationSeconds>
  -DrampSeconds=<profile.rampSeconds>
```

Recorded execution metadata:

```text
mavenProcessStartedAt
mavenProcessFinishedAt
exitCode
reportDirectory   # the directory produced by this invocation
```

`mavenProcessStartedAt` is never the measurement anchor.

## 8. Which Gatling report

`load-tests/target/gatling/` accumulates one directory per run. The anchor and the Gatling totals come from the directory created by this Maven invocation.

Selection rule:

- the directory name starts with the simulation's simple name and a hyphen, for this workload `createordersimulation-`
- the embedded timestamp is parsed with the rule in section 9
- that timestamp is greater than or equal to `mavenProcessStartedAt`
- if several directories match, use the earliest timestamp that is still greater than or equal to `mavenProcessStartedAt`

An older directory from a previous run is not a valid anchor for the current run.

## 9. Simulation start

The anchor is the Gatling simulation start, taken from that directory name.

```text
createordersimulation-20261006092234073
```

Pattern `yyyyMMddHHmmssSSS`, timezone UTC:

```text
20261006092234073 → 2026-10-06T09:22:34.073Z
```

Do not interpret the digits in the host zone, the JVM default zone, or a user zone. Normalize to `Instant` immediately.

If the report has usable run-level totals but this timestamp cannot be parsed:

```text
status         = COMPLETED
evidenceStatus = PARTIAL
windows        = UNAVAILABLE
reason         = SIMULATION_START_UNAVAILABLE
```

Gatling totals remain. Do not substitute `mavenProcessStartedAt`.

If neither a usable anchor nor usable Gatling evidence exists, the run is terminal `FAILED` with `GATLING_RESULT_UNAVAILABLE` or `GATLING_START_FAILED`, depending on whether the process produced a report.

## 10. Windows

With `simulationStartedAt` known:

```text
RAMP 1 → HOLD 1 → RAMP 2 → HOLD 2 → RAMP 3 → HOLD 3 → RAMP 4 → HOLD 4
```

```text
RAMP:
  actualStart = previous window end, or simulationStartedAt for RAMP 1
  actualEnd   = actualStart + rampSeconds

HOLD:
  actualStart = RAMP actualEnd
  actualEnd   = actualStart + stageDurationSeconds
```

Every available window exposes `actualStart` and `actualEnd`.

Membership for raw samples, gauges, and range vectors is the same half-open interval:

```text
(actualStart, actualEnd]
```

A timestamp exactly equal to a shared boundary belongs to the window that ends at that timestamp, and not to the window that starts there. `HOLD n actualEnd` equals `RAMP n+1 actualStart`, so that instant belongs to `HOLD n`.

This matches a Prometheus range vector of length `actualEnd - actualStart` evaluated at `actualEnd`, which covers `(actualStart, actualEnd]`.

## 11. Prometheus evaluation time

A range vector looks backward from the evaluation timestamp. This query, issued at collection time, describes the last 60 seconds before collection, not an earlier ramp or hold:

```promql
count_over_time(<source-series>[60s])
```

Every window-scoped Prometheus query is evaluated at:

```text
evaluationTime = window.actualEnd
```

using either the PromQL `@` modifier or the Query API `time` parameter. One mechanism is chosen and used for every query in the service.

```text
measurement window: (actualStart, actualEnd]

count_over_time(<source-series>[<windowDuration>]) @ actualEnd
rate(<source-series>[<windowDuration>])            @ actualEnd
increase(<source-series>[<windowDuration>])        @ actualEnd
```

`windowDuration = actualEnd - actualStart`. The range vector is never longer than the window and is never widened into the neighbor to force a result.

The same evaluation time applies to histogram expressions built from `rate()`.

## 12. Sample sufficiency

`window exists` does not mean `metric is measurable`.

For `rate()`, `increase()`, and histogram calculations derived from `rate()`:

**Pre-query.** If `windowDuration < 2 × effectiveScrapeInterval`, or the chosen range vector would exceed `windowDuration`:

```text
availability = UNAVAILABLE
reason       = INSUFFICIENT_SAMPLES
```

The derived query is not used. A 5-second ramp with a 15-second scrape interval fails this check. Its rate, increase, and histogram values stay unavailable. They are not filled from the following hold.

**Post-query.** `actualSampleCount` is the number of raw samples of the source series whose timestamps fall in `(actualStart, actualEnd]`, before `rate()`, `increase()`, or `histogram_quantile()`.

It is not:

- the number of values returned by `rate()`, `increase()`, or `histogram_quantile()`
- the number of series in an instant-query response
- the cardinality of the aggregated result

A suitable check, evaluated at `actualEnd` with the same selectors as the derived query:

```promql
count_over_time(<source-series>[<windowDuration>])
```

If `actualSampleCount < 2`, the result is `UNAVAILABLE / INSUFFICIENT_SAMPLES` even when `rate()` or `increase()` returned an extrapolated number.

Source series:

| Calculation | Raw samples counted on |
|---|---|
| `rate(counter)` | that counter |
| `increase(counter)` | that counter |
| `histogram_quantile(rate(bucket))` | every bucket series with the same selectors except `le`, and the `_count` series |
| `rate(histogram_sum) / rate(histogram_count)` | `_sum` and `_count` |

The histogram metric is available only when each of those series passes. A `histogram_quantile` result of `NaN` is `UNAVAILABLE` with reason `NOT_A_NUMBER`. It is not stored as a value and not replaced with zero.

`effectiveScrapeInterval` comes from one documented source for the whole run: explicit configuration or Prometheus target metadata. It is not guessed per query. Evidence may include the interval that was used.

`increase()` is `COUNTER_DELTA`, `method=increase()`, `approximate=true`. A missing series is `SERIES_NOT_FOUND`, not zero.

## 13. Gauges and lookback

An instant query at `actualEnd` can return the newest sample inside Prometheus `lookbackDelta` (commonly 5 minutes). That sample may belong to an earlier window.

For every window-scoped gauge, the adapter keeps both the value and the selected sample timestamp.

```text
selectedSampleTimestamp ∈ (actualStart, actualEnd]  → AVAILABLE
timestamp outside the interval                       → UNAVAILABLE / SAMPLE_OUTSIDE_WINDOW
no sample selected                                   → UNAVAILABLE / SERIES_NOT_FOUND
```

`evaluationTime` is when the expression runs. `selectedSampleTimestamp` is the timestamp of the raw sample. Window membership is decided from the sample timestamp.

`SAMPLE_OUTSIDE_WINDOW` is only for a selected sample outside the interval. It is not used for a missing series or for too few samples.

A window mean or max over gauge samples uses only samples inside `(actualStart, actualEnd]`.

This applies to Hikari active, Hikari pending, system CPU, process CPU, Hikari usage max, and any other `GAUGE_SAMPLE` or stateful gauge in the acceptance package.

Hikari usage max stays `MAX_OBSERVED_GAUGE` with `windowScopedOrigin=false`. A scrape inside the window does not prove the spike started inside the window. The Micrometer gauge can still show a decayed maximum from earlier.

## 14. Gatling evidence

Gatling 3.15 evidence for this workload is run-level:

```text
total requests
total OK
total KO
global assertion outcome
configured assertion thresholds
```

Do not invent per-window client percentiles from the binary `simulation.log`.

The simulation treats `200` and `201` as success, so an HTTP `429` is a Gatling KO. KO does not name the server-side limiter.

The acceptance simulation asserts global failed requests below 0.5% and global p95 below 200 ms. Those thresholds are returned as configured. A failed assertion can make `mvn gatling:test` exit 1. If the report is usable:

```text
status            = COMPLETED
assertion status  = FAILED
```

The same status is used when the target was unreachable, Gatling finished, and the report contains KO or assertion results. That outcome is experiment evidence. It is not `TARGET_UNAVAILABLE`, and that code is not a terminal run failure.

Gatling KO is run-level. Bulkhead, rate-limit, and pool-exhausted deltas are per window. RunProfile does not place them side by side as if they shared a window. Summing window deltas and comparing the sum with global KO is left to the AI.

## 15. Spring Boot acceptance series

For `target.type=SPRING_BOOT`, this package is normative. Names are the series exported by the acceptance service. Absent series stay absent.

HTTP timing series: `http_server_requests_seconds`. Successful latency uses label `status="201"`. Fast `429` responses stay out of that latency aggregation. Throughput is server-observed request rate by HTTP status and is separate from any Gatling achieved rate.

| Evidence | Series | Kind | Window rule |
|---|---|---|---|
| Achieved RPS by status | `http_server_requests_seconds_count` | counter `rate()` | both sample checks |
| HTTP mean, p95, p99 for status 201 | `http_server_requests_seconds_bucket` | histogram via `rate()` | both sample checks; `NaN` → `NOT_A_NUMBER` |
| Hikari active | `hikaricp_connections_active` | gauge | sample timestamp in window |
| Hikari pending | `hikaricp_connections_pending` | gauge | sample timestamp in window |
| Hikari acquire p99 | `hikaricp_connections_acquire_seconds` | histogram | both sample checks |
| Hikari usage mean | `hikaricp_connections_usage_seconds` | histogram | both sample checks |
| Hikari usage max | `hikaricp_connections_usage_seconds_max` | `MAX_OBSERVED_GAUGE` | sample timestamp in window; `windowScopedOrigin=false` |
| Bulkhead reject delta | `order_bulkhead_rejects_total` | `increase()` | both sample checks |
| Rate-limit reject delta | `outbox_rate_limit_rejects_total` | `increase()` | both sample checks |
| Pool-exhausted reject delta | `outbox_pool_exhausted_rejects_total` | `increase()` | both sample checks |
| System CPU | `system_cpu_usage` | gauge | sample timestamp in window |
| Process CPU | `process_cpu_usage` | gauge | sample timestamp in window |

Label selectors that identify the job, URI, and method stay in the Prometheus adapter next to these names. They are not invented per query.

`POSTGRES` and `KAFKA` targets do not receive Hikari fields as zeros. Their packages are outside v1.0 unless an acceptance run explicitly asks for them.

## 16. Partial evidence

Collection is progressive. Evidence already stored survives a later failure.

| Situation | Run status | Evidence status |
|---|---|---|
| Gatling report usable, Prometheus down | `COMPLETED` | `PARTIAL`, reason `PROMETHEUS_UNAVAILABLE` |
| Gatling totals usable, anchor unreadable | `COMPLETED` | `PARTIAL`, reason `SIMULATION_START_UNAVAILABLE` on windows |
| One metric undersampled | `COMPLETED` | other metrics unchanged; that metric `INSUFFICIENT_SAMPLES` |
| One gauge sample outside the window | `COMPLETED` | that metric `SAMPLE_OUTSIDE_WINDOW` |
| Target unreachable, Gatling report usable | `COMPLETED` | assertion status may be `FAILED` |
| Gatling did not start or produced no usable report | `FAILED` | `GATLING_START_FAILED` or `GATLING_RESULT_UNAVAILABLE` |

`FAILED` means RunProfile could not execute or recover a usable experiment result. It does not mean the application missed an SLO.

## 17. Lifecycle

```text
QUEUED → RUNNING → COLLECTING → COMPLETED
```

`FAILED` may be reached from `QUEUED`, `RUNNING`, or `COLLECTING`.

A run holds `runId`, `status`, the accepted request, `createdAt`, `startedAt`, `simulationStartedAt` when known, `finishedAt`, evidence, and failure when the run is terminal `FAILED`.

Transitions are domain operations: `start`, `markSimulationStarted`, `startCollecting`, `complete`, `fail`. Status is not assigned through a public setter.

## 18. Codes

Terminal run failures:

```text
INVALID_REQUEST
WORKLOAD_NOT_FOUND
WORKLOAD_NOT_ALLOWED
GATLING_START_FAILED
GATLING_RESULT_UNAVAILABLE
RESULT_PARSE_FAILED
RUN_NOT_FOUND
INTERNAL_ERROR
```

`INVALID_REQUEST`, `WORKLOAD_NOT_FOUND`, and `WORKLOAD_NOT_ALLOWED` are returned on `POST` and do not create a run. The others are stored on a `FAILED` run.

Synchronous admission code, no run created:

```text
EXECUTION_REJECTED
```

Evidence availability reasons:

```text
PROMETHEUS_UNAVAILABLE
SIMULATION_START_UNAVAILABLE
INSUFFICIENT_SAMPLES
SERIES_NOT_FOUND
SAMPLE_OUTSIDE_WINDOW
NOT_A_NUMBER
```

`GATLING_FAILED` is not a code. Maven exit code 1 from an assertion is not a terminal failure when the report is usable.

## 19. Admission, storage, retention

```text
POST
 ├─ validate
 │    └─ invalid → 400, no run, key not consumed
 ├─ idempotency lookup of an already accepted command
 │    ├─ same request → same runId
 │    └─ different request → 409
 └─ atomic admission
      ├─ no capacity → 503 EXECUTION_REJECTED, key not consumed
      └─ accepted → store idempotency record, Run(QUEUED), enqueue, 202
```

The idempotent body is the `202` and `runId`. It does not replace `GET` evidence.

Storage stays split:

```text
Idempotency storage → Caffeine via spring-boot-idempotency-starter
Run state/results   → InMemoryRunRepository
Execution queue     → bounded ThreadPoolTaskExecutor
```

Repository port:

```java
public interface RunRepository {
    Run save(Run run);
    Optional<Run> findById(RunId runId);
    Run update(Run run);
}
```

Configuration, all explicit, none left to library defaults:

```text
maxProfileDuration
startupAllowance
collectionAllowance
executionTimeout
clientRetryWindow
runRetention
idempotencyRetention
queueCapacity
effectiveScrapeInterval   # when not read from Prometheus metadata
```

```text
runRetention           > executionTimeout + clientRetryWindow
idempotencyRetention   > executionTimeout + clientRetryWindow
idempotencyRetention   <= runRetention
```

A supported retry must not resolve to a run that has already been evicted. After expiry, `GET` is `404 RUN_NOT_FOUND`.

Restart may drop idempotency records, queued runs, in-flight state, and completed results. That limitation is documented. There is no retry that pretends the state survived.

Executor:

```text
corePoolSize = 1
maxPoolSize  = 1
queueCapacity = explicit positive value
```

One experiment runs at a time. Do not use an unbounded queue, the default `@Async` executor, or the Java common pool.

## 20. Architecture

```text
HTTP → API adapter → application
                         ├─ RunRepository      → in-memory
                         ├─ WorkloadRunner     → Gatling/Maven
                         └─ TelemetryProvider  → Prometheus
```

Dependency direction: infrastructure depends on application, application depends on domain.

Domain code does not depend on Spring HTTP, Maven, Gatling, Prometheus, Caffeine, OpenTelemetry, the executor implementation, or JSON.

Ports:

```java
public interface WorkloadRunner {
    WorkloadExecutionResult execute(
        WorkloadDefinition workload,
        LoadProfile profile,
        Target target
    );
}

public interface TelemetryProvider {
    TelemetryEvidence collect(
        TelemetryTarget target,
        MeasurementWindow window
    );
}
```

`LoadProfile` is the four-RPS `steps` contract. It is not a universal load model. A future k6 or JMeter adapter needs its own profile when a real workload requires it.

Suggested split: `RunProfileController`, `StartRunUseCase`, `RunExecutor`, `WorkloadRegistry`, `GatlingWorkloadRunner`, `SimulationAnchorResolver`, `GatlingEvidenceParser`, `PrometheusTelemetryProvider`, `MetricMeasurabilityPolicy`, `SpringBootEvidenceMapper`, `RunRepository`.

No single class owns HTTP, process execution, PromQL, parsing, and state transitions. No `Utils` dumping ground. Idempotency behavior stays in the starter.

Lombok, where it removes boilerplate: `@Slf4j`, `@RequiredArgsConstructor`, `@Getter`, `@Builder`, `@Value`. Records are appropriate for immutable values. Domain types do not use `@Data` or `@Setter`.

## 21. Tracing and logs

OpenTelemetry, Micrometer Tracing, OTLP, W3C `traceparent` and `tracestate`. Context crosses the executor boundary. Trace identifiers stay out of business DTOs and the run domain model.

Spans: HTTP request → run execution → `workload.execute`, `simulation.anchor.resolve`, `evidence.parse`, `telemetry.collect` (`prometheus.sample-count`, `prometheus.metric-query`), `evidence.aggregate`.

Logs are English, SLF4J and Logback. Correlation fields live in MDC: `traceId`, `spanId`, `runId`, `idempotencyKey`, `targetId`. Async execution propagates MDC.

Events: run admitted, queued, started, Maven process started, anchor resolved, anchor unavailable, workload completed, assertions evaluated, telemetry started, metric unavailable, collection completed, telemetry partial, run completed, run failed, admission rejected.

Local profile: human-readable console. Container profile: JSON to stdout. Do not log every Prometheus sample or every Gatling request. Do not log secrets.

## 22. Tests

Cover at least:

- profile validation, including non-positive RPS and duration, `profileDuration` over the maximum, and a non-HTTP `baseUrl`
- four-stage window construction and `(actualStart, actualEnd]` boundary ownership: a timestamp equal to `HOLD n actualEnd` belongs to `HOLD n` only
- UTC directory timestamp, including a run under a non-UTC JVM default zone
- report selection ignores directories older than `mavenProcessStartedAt`
- missing anchor with usable Gatling totals → `COMPLETED` + `PARTIAL`
- Maven start is never used as the anchor
- pre-query rejection of a 5-second ramp at a 15-second scrape interval, with no read of the next hold
- post-query rejection when the window is long enough but raw `actualSampleCount < 2`
- sample count taken from the source series, not from instant-query cardinality
- histogram availability requires bucket series and `_count`; `NaN` becomes `NOT_A_NUMBER`
- gauge sample outside the window → `SAMPLE_OUTSIDE_WINDOW`; no sample → `SERIES_NOT_FOUND`
- usage max is `MAX_OBSERVED_GAUGE` with `windowScopedOrigin=false`
- assertion exit code 1 with a usable report stays `COMPLETED`
- unreachable target with a usable report stays `COMPLETED`
- same accepted key and same body returns the same `runId`; a different body returns `409`
- queue overflow returns `503`, creates no run, and leaves the key reusable
- concurrent duplicate accepts create one run
- one running experiment
- retention expiry returns `404 RUN_NOT_FOUND`
- acceptance series names and the `status="201"` latency selector
- trace context crosses the executor boundary where practical

External Gatling and Prometheus are test doubles or containers in these tests. The acceptance scenario in section 23 is a separate benchmark against the real workload.

## 23. Acceptance scenario

Repeat the Servlet `POST /api/v1/orders` investigation against `spring-transactional-outbox-kafka` through this API.

Workload descriptor: reactor root of that project, module `load-tests`, class `com.kholodilin.outbox.loadtests.CreateOrderSimulation`, profile `steps`.

`baseUrl` is an address the Maven process can open. On the original host that was `http://127.0.0.1:8090`.

The report directory of that invocation is parsed as UTC and becomes `simulationStartedAt`. Windows are `RAMP` and `HOLD` for each of the four RPS levels, each `(actualStart, actualEnd]`.

For `rampSeconds=5` and a scrape interval near 15 seconds, multi-sample ramp metrics are `INSUFFICIENT_SAMPLES` before the query. Longer windows still require two raw source samples after the query.

The agent receives run-level Gatling requests, OK, KO, and assertion status, plus the Spring Boot package in section 15 where each series is measurable. RunProfile does not diagnose the bottleneck.

## 24. Research benchmark

Repeat the baseline investigation with an agent that calls RunProfile. Measure tool calls, number of experiments, elapsed time, dead-end operations, AI decisions, and tokens or cost when those figures exist. Compare with the original generic-agent trajectory. An estimated call reduction is not a result until it is measured.

## 25. Definition of done

1. The service is independently deployable under `perf-engineer-ai/tools/run-profile/`.
2. `POST /api/v1/runs` validates first and returns `202` with `runId` only after admission.
3. Invalid requests return `400` and do not consume the idempotency key.
4. Queue overflow returns `503 EXECUTION_REJECTED` and does not consume the key.
5. The profile is the four-RPS `steps` contract, and duration is checked against `maxProfileDuration`.
6. The workload comes from the allowlist. Maven is started at the reactor root with module, FQCN, `profile=steps`, `baseUrl`, and all six profile properties.
7. The Gatling directory used for the anchor is the one produced by that invocation.
8. The directory timestamp is `yyyyMMddHHmmssSSS` in UTC and does not depend on the JVM zone.
9. Maven process start is never the simulation anchor.
10. A missing anchor with usable Gatling totals yields `COMPLETED` + `PARTIAL`.
11. Windows are `(actualStart, actualEnd]`. A shared boundary belongs to the window that ends there.
12. Historical PromQL is evaluated at `window.actualEnd`.
13. Multi-sample metrics pass both the duration gate and the raw-sample gate.
14. `actualSampleCount` counts raw source samples inside the window.
15. Gauge values require a selected sample timestamp inside the same interval, or `SAMPLE_OUTSIDE_WINDOW`.
16. A missing series is `SERIES_NOT_FOUND`. A `NaN` quantile is `NOT_A_NUMBER`. Neither becomes zero.
17. The acceptance response uses the series and the `status="201"` selector in section 15.
18. Hikari usage max is `MAX_OBSERVED_GAUGE` with `windowScopedOrigin=false`.
19. Gatling KO stays run-level. Rejection counters stay window deltas.
20. A Gatling assertion failure or an unreachable target with a usable report stays `COMPLETED`.
21. Prometheus loss after a usable Gatling report stays `COMPLETED` + `PARTIAL`.
22. `GET` of a `FULL` run includes Gatling totals and window bounds.
23. One worker, a bounded queue, separate run and idempotency stores, and the retention inequalities in section 19.
24. Trace context crosses HTTP and the executor. Logs are English and correlated.
25. Domain and application code do not depend on Gatling, Maven, Prometheus, Caffeine, OpenTelemetry, or HTTP.
26. Tests in section 22 pass.
27. An agent can repeat the baseline investigation through this API without hand-running Gatling, hand-parsing the report, or hand-aligning PromQL to stages.

> **AI reasons. Tools execute. Evidence stays factual.**
