package com.kholodilin.perfengineer.runprofile.domain;

import java.time.Instant;
import java.util.UUID;

public final class Run {

    private final RunId runId;
    private final StartRunCommand command;
    private final Instant createdAt;
    private RunStatus status;
    private Instant startedAt;
    private Instant simulationStartedAt;
    private Instant finishedAt;
    private RunEvidence evidence;
    private RunFailure failure;

    private Run(RunId runId, StartRunCommand command, Instant createdAt) {
        this.runId = runId;
        this.command = command;
        this.createdAt = createdAt;
        this.status = RunStatus.QUEUED;
    }

    public static Run queued(StartRunCommand command, Instant createdAt) {
        return new Run(new RunId(UUID.randomUUID().toString()), command, createdAt);
    }

    public synchronized void start(Instant at) {
        require(RunStatus.QUEUED);
        this.startedAt = at;
        this.status = RunStatus.RUNNING;
    }

    public synchronized void markSimulationStarted(Instant simulationStartedAt) {
        require(RunStatus.RUNNING);
        this.simulationStartedAt = simulationStartedAt;
    }

    public synchronized void startCollecting() {
        require(RunStatus.RUNNING);
        this.status = RunStatus.COLLECTING;
    }

    public synchronized void complete(RunEvidence evidence, Instant finishedAt) {
        require(RunStatus.COLLECTING);
        this.evidence = evidence;
        this.finishedAt = finishedAt;
        this.status = RunStatus.COMPLETED;
    }

    public synchronized void fail(FailureCode code, String message, Instant finishedAt) {
        if (status != RunStatus.QUEUED && status != RunStatus.RUNNING && status != RunStatus.COLLECTING) {
            throw new IllegalStateException("Cannot fail a run from " + status);
        }
        this.failure = new RunFailure(code, message);
        this.finishedAt = finishedAt;
        this.status = RunStatus.FAILED;
    }

    public synchronized RunId runId() {
        return runId;
    }

    public synchronized StartRunCommand command() {
        return command;
    }

    public synchronized Instant createdAt() {
        return createdAt;
    }

    public synchronized RunStatus status() {
        return status;
    }

    public synchronized Instant startedAt() {
        return startedAt;
    }

    public synchronized Instant simulationStartedAt() {
        return simulationStartedAt;
    }

    public synchronized Instant finishedAt() {
        return finishedAt;
    }

    public synchronized RunEvidence evidence() {
        return evidence;
    }

    public synchronized RunFailure failure() {
        return failure;
    }

    private void require(RunStatus expected) {
        if (status != expected) {
            throw new IllegalStateException("Expected " + expected + " but was " + status);
        }
    }
}
