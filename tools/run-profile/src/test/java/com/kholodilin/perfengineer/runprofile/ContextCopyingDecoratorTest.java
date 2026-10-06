package com.kholodilin.perfengineer.runprofile;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import com.kholodilin.perfengineer.runprofile.infrastructure.executor.ContextCopyingDecorator;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class ContextCopyingDecoratorTest {

    @Test
    void copiesTraceIdentifiersOntoTheWorkerThread() throws Exception {
        ContextCopyingDecorator decorator = new ContextCopyingDecorator();
        MDC.setContextMap(Map.of("traceId", "0af7651916cd43dd8448eb211c80319c", "spanId", "b7ad6b7169203331", "runId", "run-1"));
        AtomicReference<Map<String, String>> seen = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Runnable decorated = decorator.decorate(() -> {
            seen.set(MDC.getCopyOfContextMap());
            done.countDown();
        });
        MDC.clear();
        Thread worker = new Thread(decorated);
        worker.start();
        done.await();
        worker.join();
        assertEquals("0af7651916cd43dd8448eb211c80319c", seen.get().get("traceId"));
        assertEquals("b7ad6b7169203331", seen.get().get("spanId"));
        assertEquals("run-1", seen.get().get("runId"));
    }
}
