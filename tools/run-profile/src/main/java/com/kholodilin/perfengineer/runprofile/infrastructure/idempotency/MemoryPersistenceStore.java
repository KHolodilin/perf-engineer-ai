package com.kholodilin.perfengineer.runprofile.infrastructure.idempotency;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

import com.kholodilin.idempotency.model.IdempotencyKey;
import com.kholodilin.idempotency.model.IdempotencyRecord;
import com.kholodilin.idempotency.model.IdempotencyStatus;
import com.kholodilin.idempotency.spi.PersistenceStore;
import com.kholodilin.perfengineer.runprofile.application.ProcessingIdempotency;

public final class MemoryPersistenceStore implements PersistenceStore, ProcessingIdempotency {

    private final Clock clock;
    private final ConcurrentHashMap<IdempotencyKey, IdempotencyRecord> rows = new ConcurrentHashMap<>();
    private final ReentrantLock lock = new ReentrantLock();

    public MemoryPersistenceStore(Clock clock) {
        this.clock = clock;
    }

    @Override
    public Optional<IdempotencyRecord> find(IdempotencyKey key) {
        lock.lock();
        try {
            IdempotencyRecord record = rows.get(key);
            if (record == null || expired(record)) {
                rows.remove(key, record);
                return Optional.empty();
            }
            return Optional.of(record);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public boolean acquire(IdempotencyKey key, String requestHash, Instant createdAt, Instant expiresAt) {
        lock.lock();
        try {
            IdempotencyRecord existing = rows.get(key);
            if (existing != null && expired(existing)) {
                rows.remove(key, existing);
                existing = null;
            }
            if (existing != null) {
                return false;
            }
            rows.put(key, IdempotencyRecord.processing(key, requestHash, createdAt, expiresAt));
            return true;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void complete(IdempotencyKey key, String resultType, String resultPayload, Instant completedAt) {
        replace(key, current -> current.completed(resultType, resultPayload, completedAt));
    }

    @Override
    public void reject(IdempotencyKey key, String errorCode, String detailsPayload, Instant completedAt) {
        replace(key, current -> current.rejected(errorCode, detailsPayload, completedAt));
    }

    @Override
    public void discardProcessing(String operation, String key) {
        discardProcessing(new IdempotencyKey(operation, key));
    }

    public void discardProcessing(IdempotencyKey key) {
        lock.lock();
        try {
            IdempotencyRecord record = rows.get(key);
            if (record != null && record.status() == IdempotencyStatus.PROCESSING) {
                rows.remove(key, record);
            }
        } finally {
            lock.unlock();
        }
    }

    private void replace(IdempotencyKey key, java.util.function.Function<IdempotencyRecord, IdempotencyRecord> next) {
        lock.lock();
        try {
            IdempotencyRecord current = rows.get(key);
            if (current == null || current.status() != IdempotencyStatus.PROCESSING) {
                throw new IllegalStateException("Expected a PROCESSING idempotency record for " + key);
            }
            rows.put(key, next.apply(current));
        } finally {
            lock.unlock();
        }
    }

    private boolean expired(IdempotencyRecord record) {
        return record.expiresAt() != null && !clock.instant().isBefore(record.expiresAt());
    }
}
