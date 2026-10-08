package com.sparta.logistics.application.query.service;

import com.sparta.logistics.application.query.dto.ExternalDataSource;
import com.sparta.logistics.application.query.dto.ExternalLookupResponse;
import com.sparta.logistics.common.code.ErrorResponseCode;
import com.sparta.logistics.common.exception.ApiException;
import com.sparta.logistics.common.metrics.OrderPerformanceMetrics;
import com.sparta.logistics.infrastructure.cache.CachedExternalValue;
import com.sparta.logistics.infrastructure.cache.ExternalQueryCache;
import com.sparta.logistics.infrastructure.feign.client.DeliveryClient;
import com.sparta.logistics.infrastructure.feign.client.ProductClient;
import com.sparta.logistics.infrastructure.feign.dto.delivery.DeliveryStatus;
import com.sparta.logistics.infrastructure.feign.dto.delivery.DeliveryStatusResponse;
import com.sparta.logistics.infrastructure.feign.dto.product.ProductResponse;
import com.sparta.logistics.presentation.common.dto.response.GeneralResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderExternalQueryServiceTest {

    @Mock
    private ProductClient productClient;

    @Mock
    private DeliveryClient deliveryClient;

    @Mock
    private ExternalQueryCache externalQueryCache;

    @Mock
    private OrderPerformanceMetrics performanceMetrics;

    @InjectMocks
    private OrderExternalQueryService orderExternalQueryService;

    @Test
    @DisplayName("상품 정상 조회 결과를 캐시에 저장하고 LIVE 응답을 반환한다")
    void getProduct_success_cachesAndReturnsLiveResponse() {
        UUID productId = UUID.randomUUID();
        ProductResponse product = product(productId, "최신 상품");
        when(productClient.getProduct(productId)).thenReturn(new GeneralResponse<>("OK", product));

        ExternalLookupResponse<ProductResponse> response = orderExternalQueryService.getProduct(productId);

        assertThat(response.data()).isEqualTo(product);
        assertThat(response.source()).isEqualTo(ExternalDataSource.LIVE);
        assertThat(response.fetchedAt()).isNotNull();
        verify(externalQueryCache).putProduct(productId, product, response.fetchedAt());
    }

    @Test
    @DisplayName("상품 조회 실패 시 최근 캐시가 있으면 CACHE 응답을 반환한다")
    void fallbackProduct_cacheExists_returnsCachedResponse() {
        UUID productId = UUID.randomUUID();
        Instant fetchedAt = Instant.parse("2026-10-06T01:00:00Z");
        ProductResponse cachedProduct = product(productId, "캐시 상품");
        RuntimeException cause = new RuntimeException("상품 서비스 연결 실패");
        when(externalQueryCache.getProduct(productId))
                .thenReturn(Optional.of(new CachedExternalValue<>(cachedProduct, fetchedAt)));

        ExternalLookupResponse<ProductResponse> response = invokeFallbackProduct(productId, cause);

        assertThat(response.data()).isEqualTo(cachedProduct);
        assertThat(response.source()).isEqualTo(ExternalDataSource.CACHE);
        assertThat(response.fetchedAt()).isEqualTo(fetchedAt);
    }

    @Test
    @DisplayName("상품 조회 실패 시 캐시가 없으면 기존 조회 예외를 유지한다")
    void fallbackProduct_cacheMissing_throwsExistingException() {
        UUID productId = UUID.randomUUID();
        RuntimeException cause = new RuntimeException("상품 서비스 연결 실패");
        when(externalQueryCache.getProduct(productId)).thenReturn(Optional.empty());

        ApiException exception = catchThrowableOfType(
                ApiException.class,
                () -> invokeFallbackProduct(productId, cause)
        );

        assertThat(exception.getResponseCode()).isEqualTo(ErrorResponseCode.ORDER_PRODUCT_LOOKUP_FAILED);
        assertThat(exception.getCause()).isSameAs(cause);
    }

    @Test
    @DisplayName("배송 상태 조회 실패 시 최근 캐시가 있으면 CACHE 응답을 반환한다")
    void fallbackDeliveryStatus_cacheExists_returnsCachedResponse() {
        UUID deliveryId = UUID.randomUUID();
        Instant fetchedAt = Instant.parse("2026-10-06T01:00:00Z");
        DeliveryStatusResponse cachedStatus = new DeliveryStatusResponse(deliveryId, DeliveryStatus.DELIVERING);
        RuntimeException cause = new RuntimeException("배송 서비스 연결 실패");
        when(externalQueryCache.getDeliveryStatus(deliveryId))
                .thenReturn(Optional.of(new CachedExternalValue<>(cachedStatus, fetchedAt)));

        ExternalLookupResponse<DeliveryStatusResponse> response =
                invokeFallbackDeliveryStatus(deliveryId, cause);

        assertThat(response.data()).isEqualTo(cachedStatus);
        assertThat(response.source()).isEqualTo(ExternalDataSource.CACHE);
        assertThat(response.fetchedAt()).isEqualTo(fetchedAt);
    }

    @Test
    @DisplayName("배송 상태 정상 조회 결과를 캐시에 저장하고 LIVE 응답을 반환한다")
    void getDeliveryStatus_success_cachesAndReturnsLiveResponse() {
        UUID deliveryId = UUID.randomUUID();
        DeliveryStatusResponse deliveryStatus =
                new DeliveryStatusResponse(deliveryId, DeliveryStatus.DELIVERING);
        when(deliveryClient.getDeliveryStatus(deliveryId))
                .thenReturn(new GeneralResponse<>("OK", deliveryStatus));

        ExternalLookupResponse<DeliveryStatusResponse> response =
                orderExternalQueryService.getDeliveryStatus(deliveryId);

        assertThat(response.data()).isEqualTo(deliveryStatus);
        assertThat(response.source()).isEqualTo(ExternalDataSource.LIVE);
        assertThat(response.fetchedAt()).isNotNull();
        verify(externalQueryCache).putDeliveryStatus(deliveryId, deliveryStatus, response.fetchedAt());
    }

    @Test
    @DisplayName("배송 상태 조회 실패 시 캐시가 없으면 기존 조회 예외를 유지한다")
    void fallbackDeliveryStatus_cacheMissing_throwsExistingException() {
        UUID deliveryId = UUID.randomUUID();
        RuntimeException cause = new RuntimeException("배송 서비스 연결 실패");
        when(externalQueryCache.getDeliveryStatus(deliveryId)).thenReturn(Optional.empty());

        ApiException exception = catchThrowableOfType(
                ApiException.class,
                () -> invokeFallbackDeliveryStatus(deliveryId, cause)
        );

        assertThat(exception.getResponseCode())
                .isEqualTo(ErrorResponseCode.ORDER_DELIVERY_STATUS_LOOKUP_FAILED);
        assertThat(exception.getCause()).isSameAs(cause);
    }

    @Test
    @DisplayName("장애 복구 후 정상 조회 결과로 상품 캐시를 갱신한다")
    void getProduct_afterRecovery_updatesCacheWithLatestResponse() {
        UUID productId = UUID.randomUUID();
        ProductResponse oldProduct = product(productId, "이전 상품");
        ProductResponse latestProduct = product(productId, "최신 상품");
        when(productClient.getProduct(productId))
                .thenReturn(new GeneralResponse<>("OK", oldProduct))
                .thenReturn(new GeneralResponse<>("OK", latestProduct));

        orderExternalQueryService.getProduct(productId);
        ExternalLookupResponse<ProductResponse> latestResponse = orderExternalQueryService.getProduct(productId);

        ArgumentCaptor<ProductResponse> productCaptor = ArgumentCaptor.forClass(ProductResponse.class);
        verify(externalQueryCache, times(2)).putProduct(eq(productId), productCaptor.capture(), any(Instant.class));
        assertThat(productCaptor.getAllValues()).containsExactly(oldProduct, latestProduct);
        assertThat(latestResponse.data()).isEqualTo(latestProduct);
        assertThat(latestResponse.source()).isEqualTo(ExternalDataSource.LIVE);
    }

    @SuppressWarnings("unchecked")
    private ExternalLookupResponse<ProductResponse> invokeFallbackProduct(UUID productId, Throwable cause) {
        return (ExternalLookupResponse<ProductResponse>) ReflectionTestUtils.invokeMethod(
                orderExternalQueryService,
                "fallbackProduct",
                productId,
                cause
        );
    }

    @SuppressWarnings("unchecked")
    private ExternalLookupResponse<DeliveryStatusResponse> invokeFallbackDeliveryStatus(
            UUID deliveryId,
            Throwable cause
    ) {
        return (ExternalLookupResponse<DeliveryStatusResponse>) ReflectionTestUtils.invokeMethod(
                orderExternalQueryService,
                "fallbackDeliveryStatus",
                deliveryId,
                cause
        );
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
