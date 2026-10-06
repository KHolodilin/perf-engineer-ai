package com.kholodilin.perfengineer.runprofile.infrastructure.executor;

import java.util.Map;

import io.micrometer.context.ContextSnapshot;
import io.micrometer.context.ContextSnapshotFactory;
import org.slf4j.MDC;
import org.springframework.core.task.TaskDecorator;

public final class ContextCopyingDecorator implements TaskDecorator {

    private final ContextSnapshotFactory snapshots = ContextSnapshotFactory.builder().build();

    @Override
    public Runnable decorate(Runnable runnable) {
        Map<String, String> callerContext = MDC.getCopyOfContextMap();
        ContextSnapshot snapshot = snapshots.captureAll();
        return () -> {
            Map<String, String> previous = MDC.getCopyOfContextMap();
            if (callerContext != null) {
                MDC.setContextMap(callerContext);
            } else {
                MDC.clear();
            }
            try (ContextSnapshot.Scope scope = snapshot.setThreadLocals()) {
                runnable.run();
            } finally {
                if (previous != null) {
                    MDC.setContextMap(previous);
                } else {
                    MDC.clear();
                }
            }
        };
    }
}
