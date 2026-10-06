package com.sparta.logistics.infrastructure.cache;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparta.logistics.infrastructure.feign.dto.delivery.DeliveryStatusResponse;
import com.sparta.logistics.infrastructure.feign.dto.product.ProductResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Component
public class ExternalQueryCache {

    private static final String PRODUCT_KEY_PREFIX = "external-query:product:";
    private static final String DELIVERY_STATUS_KEY_PREFIX = "external-query:delivery-status:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final String serviceName;
    private final Duration productTtl;
    private final Duration deliveryStatusTtl;

    public ExternalQueryCache(
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            @Value("${spring.application.name}") String serviceName,
            @Value("${message.external-query-cache.product-ttl-seconds:300}") long productTtlSeconds,
            @Value("${message.external-query-cache.delivery-status-ttl-seconds:30}") long deliveryStatusTtlSeconds
    ) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.serviceName = serviceName;
        this.productTtl = Duration.ofSeconds(productTtlSeconds);
        this.deliveryStatusTtl = Duration.ofSeconds(deliveryStatusTtlSeconds);
    }

    public void putProduct(UUID productId, ProductResponse data, Instant fetchedAt) {
        put(productKey(productId), new CachedExternalValue<>(data, fetchedAt), productTtl);
    }

    public Optional<CachedExternalValue<ProductResponse>> getProduct(UUID productId) {
        return get(productKey(productId), ProductResponse.class);
    }

    public void putDeliveryStatus(UUID deliveryId, DeliveryStatusResponse data, Instant fetchedAt) {
        put(deliveryStatusKey(deliveryId), new CachedExternalValue<>(data, fetchedAt), deliveryStatusTtl);
    }

    public Optional<CachedExternalValue<DeliveryStatusResponse>> getDeliveryStatus(UUID deliveryId) {
        return get(deliveryStatusKey(deliveryId), DeliveryStatusResponse.class);
    }

    private void put(String key, CachedExternalValue<?> value, Duration ttl) {
        try {
            redisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(value), ttl);
        } catch (Exception e) {
            log.warn("외부 조회 결과 캐시 저장에 실패했습니다. key={}", key, e);
        }
    }

    private <T> Optional<CachedExternalValue<T>> get(String key, Class<T> dataType) {
        try {
            String json = redisTemplate.opsForValue().get(key);
            if (json == null) {
                return Optional.empty();
            }

            JavaType cacheType = objectMapper.getTypeFactory()
                    .constructParametricType(CachedExternalValue.class, dataType);
            return Optional.of(objectMapper.readValue(json, cacheType));
        } catch (Exception e) {
            log.warn("외부 조회 결과 캐시 조회에 실패했습니다. key={}", key, e);
            return Optional.empty();
        }
    }

    private String productKey(UUID productId) {
        return serviceName + ":" + PRODUCT_KEY_PREFIX + productId;
    }

    private String deliveryStatusKey(UUID deliveryId) {
        return serviceName + ":" + DELIVERY_STATUS_KEY_PREFIX + deliveryId;
    }
}
