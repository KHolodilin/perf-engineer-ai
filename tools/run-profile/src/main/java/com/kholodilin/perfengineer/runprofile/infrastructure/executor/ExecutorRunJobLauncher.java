package com.kholodilin.perfengineer.runprofile.infrastructure.executor;

import com.kholodilin.perfengineer.runprofile.application.RunExecutor;
import com.kholodilin.perfengineer.runprofile.application.RunJobLauncher;
import com.kholodilin.perfengineer.runprofile.domain.RunId;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

public final class ExecutorRunJobLauncher implements RunJobLauncher {

    private final ThreadPoolTaskExecutor executor;
    private final RunExecutor runExecutor;
    private final ObservationRegistry observationRegistry;

    public ExecutorRunJobLauncher(
            ThreadPoolTaskExecutor executor,
            RunExecutor runExecutor,
            ObservationRegistry observationRegistry) {
        this.executor = executor;
        this.runExecutor = runExecutor;
        this.observationRegistry = observationRegistry;
    }

    @Override
    public void submit(RunId runId) {
        executor.execute(() -> Observation.createNotStarted("run.execution", observationRegistry)
                .observe(() -> runExecutor.execute(runId)));
    }
}
