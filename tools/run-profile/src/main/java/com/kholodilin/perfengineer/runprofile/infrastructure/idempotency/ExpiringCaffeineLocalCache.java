package com.kholodilin.perfengineer.runprofile.infrastructure.idempotency;

import java.time.Clock;
import java.time.Duration;
import java.util.Optional;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.kholodilin.idempotency.model.IdempotencyKey;
import com.kholodilin.idempotency.model.IdempotencyRecord;
import com.kholodilin.idempotency.spi.LocalCache;

public final class ExpiringCaffeineLocalCache implements LocalCache {

    private final Cache<IdempotencyKey, IdempotencyRecord> cache;
    private final Clock clock;

    public ExpiringCaffeineLocalCache(Duration ttl, Clock clock) {
        this.clock = clock;
        this.cache = Caffeine.newBuilder().expireAfterWrite(ttl).maximumSize(10_000).build();
    }

    @Override
    public Optional<IdempotencyRecord> get(IdempotencyKey key) {
        IdempotencyRecord record = cache.getIfPresent(key);
        if (record == null) {
            return Optional.empty();
        }
        if (record.expiresAt() != null && !clock.instant().isBefore(record.expiresAt())) {
            cache.invalidate(key);
            return Optional.empty();
        }
        return Optional.of(record);
    }

    @Override
    public void put(IdempotencyKey key, IdempotencyRecord record) {
        cache.put(key, record);
    }

    @Override
    public void evict(IdempotencyKey key) {
        cache.invalidate(key);
    }
}
