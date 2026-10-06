package com.kholodilin.perfengineer.runprofile.infrastructure.memory;

import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import com.kholodilin.perfengineer.runprofile.application.RunNotFoundException;
import com.kholodilin.perfengineer.runprofile.domain.Run;
import com.kholodilin.perfengineer.runprofile.domain.RunId;
import com.kholodilin.perfengineer.runprofile.domain.RunRepository;

public final class InMemoryRunRepository implements RunRepository {

    private final Duration retention;
    private final Clock clock;
    private final ConcurrentHashMap<RunId, Run> runs = new ConcurrentHashMap<>();

    public InMemoryRunRepository(Duration retention, Clock clock) {
        this.retention = retention;
        this.clock = clock;
    }

    @Override
    public Run save(Run run) {
        runs.put(run.runId(), run);
        return run;
    }

    @Override
    public Optional<Run> findById(RunId runId) {
        Run run = runs.get(runId);
        if (run == null) {
            return Optional.empty();
        }
        if (expired(run)) {
            runs.remove(runId, run);
            return Optional.empty();
        }
        return Optional.of(run);
    }

    @Override
    public Run update(Run run) {
        Run current = findById(run.runId()).orElseThrow(RunNotFoundException::new);
        if (current != run) {
            runs.put(run.runId(), run);
        }
        return run;
    }

    @Override
    public void delete(RunId runId) {
        runs.remove(runId);
    }

    private boolean expired(Run run) {
        return !clock.instant().isBefore(run.createdAt().plus(retention));
    }
}
