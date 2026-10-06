package com.kholodilin.perfengineer.runprofile.application;

import java.time.Clock;
import java.util.Optional;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

import com.kholodilin.idempotency.ExecutionResult;
import com.kholodilin.idempotency.IdempotencyService;
import com.kholodilin.idempotency.exception.IdempotencyConflictException;
import com.kholodilin.idempotency.model.IdempotencyKey;
import com.kholodilin.idempotency.model.IdempotencyRecord;
import com.kholodilin.idempotency.spi.FingerprintStrategy;
import com.kholodilin.idempotency.spi.IdempotencySerializer;
import com.kholodilin.idempotency.spi.LocalCache;
import com.kholodilin.idempotency.spi.PersistenceStore;
import com.kholodilin.perfengineer.runprofile.config.RunProfileProperties;
import com.kholodilin.perfengineer.runprofile.domain.Run;
import com.kholodilin.perfengineer.runprofile.domain.RunRepository;
import com.kholodilin.perfengineer.runprofile.domain.StartRunCommand;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class StartRunUseCase {

    static final String OPERATION = "START_RUN";

    private final ProfileValidator validator;
    private final WorkloadRegistry workloadRegistry;
    private final AdmissionGate admissionGate;
    private final RunRepository repository;
    private final RunJobLauncher launcher;
    private final IdempotencyService idempotencyService;
    private final PersistenceStore records;
    private final ProcessingIdempotency persistenceStore;
    private final LocalCache localCache;
    private final FingerprintStrategy fingerprintStrategy;
    private final IdempotencySerializer serializer;
    private final RunProfileProperties properties;
    private final Clock clock;
    private final ReentrantLock admissionLock = new ReentrantLock();

    public AcceptedRun start(String idempotencyKey, StartRunCommand command) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new RequestRejectedException("INVALID_REQUEST", "Idempotency-Key header is required", 400);
        }
        validator.validate(command);
        workloadRegistry.require(command.workloadId());

        MDC.put("idempotencyKey", idempotencyKey);
        MDC.put("targetId", command.targetId());
        admissionLock.lock();
        try {
            AcceptedRun replay = replayIfAccepted(idempotencyKey, command);
            if (replay != null) {
                return replay;
            }
            if (!admissionGate.tryAcquire()) {
                log.info("Admission rejected");
                throw new RequestRejectedException("EXECUTION_REJECTED", "Run execution queue is full", 503);
            }
            boolean enqueued = false;
            try {
                AtomicBoolean created = new AtomicBoolean(false);
                ExecutionResult<AcceptedRun> result = idempotencyService.operation(OPERATION)
                        .key(idempotencyKey)
                        .request(command)
                        .ttl(properties.getIdempotencyRetention())
                        .execute(AcceptedRun.class, () -> {
                            created.set(true);
                            Run run = Run.queued(command, clock.instant());
                            repository.save(run);
                            try {
                                launcher.submit(run.runId());
                            } catch (RejectedExecutionException ex) {
                                repository.delete(run.runId());
                                throw new RequestRejectedException(
                                        "EXECUTION_REJECTED", "Run execution queue is full", 503);
                            }
                            log.info("Run admitted");
                            log.info("Run queued");
                            return ExecutionResult.success(new AcceptedRun(run.runId().value()));
                        });
AcceptedRun accepted = result.valueOrThrow();
                        if (created.get()) {
                            enqueued = true;
                        }
                        return accepted;
            } catch (IdempotencyConflictException ex) {
                throw ex;
            } catch (RequestRejectedException ex) {
                persistenceStore.discardProcessing(OPERATION, idempotencyKey);
                localCache.evict(new IdempotencyKey(OPERATION, idempotencyKey));
                throw ex;
            } catch (RuntimeException ex) {
                persistenceStore.discardProcessing(OPERATION, idempotencyKey);
                localCache.evict(new IdempotencyKey(OPERATION, idempotencyKey));
                throw ex;
            } finally {
                if (!enqueued) {
                    admissionGate.release();
                }
            }
        } finally {
            admissionLock.unlock();
            MDC.remove("idempotencyKey");
            MDC.remove("targetId");
        }
    }

    private AcceptedRun replayIfAccepted(String idempotencyKey, StartRunCommand command) {
        IdempotencyKey key = new IdempotencyKey(OPERATION, idempotencyKey);
        Optional<IdempotencyRecord> cached = localCache.get(key);
        IdempotencyRecord record = cached.orElseGet(() -> records.find(key).orElse(null));
        if (record == null || !record.status().isTerminal()) {
            if (record != null) {
                persistenceStore.discardProcessing(key.operation(), key.key());
                localCache.evict(key);
            }
            return null;
        }
        String fingerprint = fingerprintStrategy.calculate(command);
        if (!record.requestHash().equals(fingerprint)) {
            throw new IdempotencyConflictException(key, record.requestHash(), fingerprint);
        }
        return serializer.deserialize(record.resultPayload(), AcceptedRun.class);
    }
}
