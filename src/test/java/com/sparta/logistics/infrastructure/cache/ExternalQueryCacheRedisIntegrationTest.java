package com.sparta.logistics.infrastructure.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.sparta.logistics.infrastructure.feign.dto.product.ProductResponse;
import com.sparta.logistics.infrastructure.feign.dto.delivery.DeliveryStatus;
import com.sparta.logistics.infrastructure.feign.dto.delivery.DeliveryStatusResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class ExternalQueryCacheRedisIntegrationTest {

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    private LettuceConnectionFactory connectionFactory;
    private ExternalQueryCache externalQueryCache;

    @BeforeEach
    void setUp() {
        connectionFactory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        connectionFactory.afterPropertiesSet();

        StringRedisTemplate redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();

        ObjectMapper objectMapper = JsonMapper.builder()
                .findAndAddModules()
                .build();
        externalQueryCache = new ExternalQueryCache(
                redisTemplate,
                objectMapper,
                "order-service-test",
                1,
                1
        );
    }

    @AfterEach
    void tearDown() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    @Test
    @DisplayName("상품 조회 결과와 조회 시각을 Redis에 저장하고 복원한다")
    void productCache_roundTrip_preservesDataAndFetchedAt() {
        UUID productId = UUID.randomUUID();
        Instant fetchedAt = Instant.parse("2026-10-06T01:00:00Z");
        ProductResponse product = product(productId, "캐시 상품");

        externalQueryCache.putProduct(productId, product, fetchedAt);

        CachedExternalValue<ProductResponse> cached = externalQueryCache.getProduct(productId).orElseThrow();
        assertThat(cached.data()).isEqualTo(product);
        assertThat(cached.fetchedAt()).isEqualTo(fetchedAt);
    }

    @Test
    @DisplayName("정상 조회가 복구되면 기존 상품 캐시를 최신 값으로 덮어쓴다")
    void productCache_putAgain_overwritesWithLatestData() {
        UUID productId = UUID.randomUUID();
        ProductResponse oldProduct = product(productId, "이전 상품");
        ProductResponse latestProduct = product(productId, "최신 상품");

        externalQueryCache.putProduct(productId, oldProduct, Instant.parse("2026-10-06T01:00:00Z"));
        externalQueryCache.putProduct(productId, latestProduct, Instant.parse("2026-10-06T01:01:00Z"));

        CachedExternalValue<ProductResponse> cached = externalQueryCache.getProduct(productId).orElseThrow();
        assertThat(cached.data()).isEqualTo(latestProduct);
        assertThat(cached.fetchedAt()).isEqualTo(Instant.parse("2026-10-06T01:01:00Z"));
    }

    @Test
    @DisplayName("설정한 TTL이 지나면 상품 캐시가 만료된다")
    void productCache_afterTtl_expires() throws Exception {
        UUID productId = UUID.randomUUID();
        externalQueryCache.putProduct(productId, product(productId, "만료 상품"), Instant.now());

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (externalQueryCache.getProduct(productId).isPresent() && System.nanoTime() < deadline) {
            Thread.sleep(100);
        }

        assertThat(externalQueryCache.getProduct(productId)).isEmpty();
    }

    @Test
    @DisplayName("설정한 TTL이 지나면 배송 상태 캐시가 만료된다")
    void deliveryStatusCache_afterTtl_expires() throws Exception {
        UUID deliveryId = UUID.randomUUID();
        DeliveryStatusResponse deliveryStatus =
                new DeliveryStatusResponse(deliveryId, DeliveryStatus.DELIVERING);
        externalQueryCache.putDeliveryStatus(deliveryId, deliveryStatus, Instant.now());

        CachedExternalValue<DeliveryStatusResponse> cached =
                externalQueryCache.getDeliveryStatus(deliveryId).orElseThrow();
        assertThat(cached.data()).isEqualTo(deliveryStatus);

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (externalQueryCache.getDeliveryStatus(deliveryId).isPresent()
                && System.nanoTime() < deadline) {
            Thread.sleep(100);
        }

        assertThat(externalQueryCache.getDeliveryStatus(deliveryId)).isEmpty();
    }

    private ProductResponse product(UUID productId, String name) {
        return new ProductResponse(
                productId,
                name,
                UUID.randomUUID(),
                "테스트 업체",
                Instant.now(),
                Instant.now()
        );
    }
}
