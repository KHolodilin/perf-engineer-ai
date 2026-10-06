package com.kholodilin.perfengineer.runprofile.api;

import java.util.List;

import com.kholodilin.perfengineer.runprofile.application.RequestRejectedException;
import com.kholodilin.perfengineer.runprofile.domain.Run;
import com.kholodilin.perfengineer.runprofile.domain.RunEvidence;
import com.kholodilin.perfengineer.runprofile.domain.StartRunCommand;
import com.kholodilin.perfengineer.runprofile.domain.TelemetryTarget;

import org.springframework.stereotype.Component;

@Component
public final class RunHttpMapper {

    public StartRunCommand command(StartRunRequest request) {
        if (request == null || request.workload() == null || request.target() == null
                || request.profile() == null || request.telemetry() == null) {
            throw new RequestRejectedException("INVALID_REQUEST", "Request body is incomplete", 400);
        }
        StartRunRequest.ProfileRequest profile = request.profile();
        if (profile.rps1() == null || profile.rps2() == null || profile.rps3() == null || profile.rps4() == null
                || profile.stageDurationSeconds() == null || profile.rampSeconds() == null) {
            throw new RequestRejectedException("INVALID_REQUEST", "Profile fields are required", 400);
        }
        List<TelemetryTarget> targets = request.telemetry().targets() == null
                ? List.of()
                : request.telemetry().targets().stream()
                        .map(target -> new TelemetryTarget(target.id(), target.type(), target.job()))
                        .toList();
        return new StartRunCommand(
                request.workload().id(),
                request.target().baseUrl(),
                profile.rps1(),
                profile.rps2(),
                profile.rps3(),
                profile.rps4(),
                profile.stageDurationSeconds(),
                profile.rampSeconds(),
                request.telemetry().prometheusUrl(),
                targets);
    }

    public RunResponse response(Run run) {
        RunEvidence evidence = run.evidence();
        return new RunResponse(
                run.runId().value(),
                run.status().name(),
                evidence == null ? null : evidence.evidenceStatus().name(),
                run.createdAt(),
                run.startedAt(),
                run.simulationStartedAt(),
                run.finishedAt(),
                evidence == null ? null : new RunResponse.AssertionsResponse(
                        evidence.gatlingAssertions().status().name(),
                        evidence.gatlingAssertions().maxFailedPercent(),
                        evidence.gatlingAssertions().p95Ms()),
                evidence == null ? null : new RunResponse.GatlingResponse(
                        evidence.gatling().requests(), evidence.gatling().ok(), evidence.gatling().ko()),
                evidence == null ? null : evidence.stages().stream().map(stage -> new RunResponse.StageResponse(
                        stage.stage(),
                        stage.requestedRps(),
                        stage.windows().stream().map(window -> new RunResponse.WindowResponse(
                                window.type().name(),
                                window.actualStart(),
                                window.actualEnd(),
                                window.metrics().stream().map(metric -> new RunResponse.MetricResponse(
                                        metric.name(),
                                        metric.value(),
                                        metric.unit(),
                                        metric.source(),
                                        metric.series(),
                                        metric.selector(),
                                        metric.semantics().name(),
                                        metric.method(),
                                        metric.approximate(),
                                        metric.availability().name(),
                                        metric.reason() == null ? null : metric.reason().name(),
                                        metric.temporalScope().name(),
                                        metric.windowScopedOrigin())).toList())).toList())).toList(),
                evidence == null || evidence.partialReason() == null ? null : evidence.partialReason().name(),
                run.failure() == null ? null : new RunResponse.FailureResponse(
                        run.failure().code().name(), run.failure().message()));
    }
}
