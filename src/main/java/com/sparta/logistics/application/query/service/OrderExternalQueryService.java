package com.sparta.logistics.application.query.service;

import com.sparta.logistics.application.query.dto.ExternalLookupResponse;
import com.sparta.logistics.common.code.ErrorResponseCode;
import com.sparta.logistics.common.exception.ApiException;
import com.sparta.logistics.infrastructure.cache.CachedExternalValue;
import com.sparta.logistics.infrastructure.cache.ExternalQueryCache;
import com.sparta.logistics.infrastructure.feign.client.DeliveryClient;
import com.sparta.logistics.infrastructure.feign.client.ProductClient;
import com.sparta.logistics.infrastructure.feign.dto.delivery.DeliveryStatusResponse;
import com.sparta.logistics.infrastructure.feign.dto.product.ProductResponse;
import com.sparta.logistics.presentation.common.dto.response.GeneralResponse;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderExternalQueryService {

    private final ProductClient productClient;
    private final DeliveryClient deliveryClient;
    private final ExternalQueryCache externalQueryCache;

    @CircuitBreaker(name = "productService", fallbackMethod = "fallbackProduct")
    public ExternalLookupResponse<ProductResponse> getProduct(UUID productId) {
        GeneralResponse<ProductResponse> response = productClient.getProduct(productId);
        ProductResponse product = requireData(response, ErrorResponseCode.ORDER_PRODUCT_LOOKUP_FAILED);
        Instant fetchedAt = Instant.now();

        externalQueryCache.putProduct(productId, product, fetchedAt);
        return ExternalLookupResponse.live(product, fetchedAt);
    }

    private ExternalLookupResponse<ProductResponse> fallbackProduct(UUID productId, Throwable cause) {
        return externalQueryCache.getProduct(productId)
                .map(cached -> {
                    log.warn("상품 조회에 실패하여 캐시 데이터를 반환합니다. 상품ID={}, cachedAt={}",
                            productId, cached.fetchedAt(), cause);
                    return ExternalLookupResponse.cached(cached.data(), cached.fetchedAt());
                })
                .orElseThrow(() -> {
                    log.error("상품 조회에 실패했고 사용 가능한 캐시가 없습니다. 상품ID={}", productId, cause);
                    return new ApiException(ErrorResponseCode.ORDER_PRODUCT_LOOKUP_FAILED, cause);
                });
    }

    @CircuitBreaker(name = "deliveryService", fallbackMethod = "fallbackDeliveryStatus")
    public ExternalLookupResponse<DeliveryStatusResponse> getDeliveryStatus(UUID deliveryId) {
        GeneralResponse<DeliveryStatusResponse> response = deliveryClient.getDeliveryStatus(deliveryId);
        DeliveryStatusResponse deliveryStatus = requireData(
                response,
                ErrorResponseCode.ORDER_DELIVERY_STATUS_LOOKUP_FAILED
        );
        Instant fetchedAt = Instant.now();

        externalQueryCache.putDeliveryStatus(deliveryId, deliveryStatus, fetchedAt);
        return ExternalLookupResponse.live(deliveryStatus, fetchedAt);
    }

    private ExternalLookupResponse<DeliveryStatusResponse> fallbackDeliveryStatus(
            UUID deliveryId,
            Throwable cause
    ) {
        return externalQueryCache.getDeliveryStatus(deliveryId)
                .map(cached -> {
                    log.warn("배송 상태 조회에 실패하여 캐시 데이터를 반환합니다. 배송ID={}, cachedAt={}",
                            deliveryId, cached.fetchedAt(), cause);
                    return ExternalLookupResponse.cached(cached.data(), cached.fetchedAt());
                })
                .orElseThrow(() -> {
                    log.error("배송 상태 조회에 실패했고 사용 가능한 캐시가 없습니다. 배송ID={}", deliveryId, cause);
                    return new ApiException(ErrorResponseCode.ORDER_DELIVERY_STATUS_LOOKUP_FAILED, cause);
                });
    }

    private <T> T requireData(GeneralResponse<T> response, ErrorResponseCode errorCode) {
        if (response == null || response.data() == null) {
            throw new ApiException(errorCode);
        }
        return response.data();
    }
}
