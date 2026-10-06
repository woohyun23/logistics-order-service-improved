package com.sparta.logistics.infrastructure.cache;

import java.time.Instant;

public record CachedExternalValue<T>(
        T data,
        Instant fetchedAt
) {
}
