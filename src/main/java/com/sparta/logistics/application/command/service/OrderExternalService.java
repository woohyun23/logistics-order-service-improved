package com.sparta.logistics.application.command.service;

import com.sparta.logistics.infrastructure.feign.client.DeliveryClient;
import com.sparta.logistics.infrastructure.feign.client.HubClient;
import com.sparta.logistics.infrastructure.feign.client.ProductClient;
import com.sparta.logistics.infrastructure.feign.dto.delivery.CancelDeliveryRequest;
import com.sparta.logistics.infrastructure.feign.dto.delivery.CreateDeliveryRequest;
import com.sparta.logistics.infrastructure.feign.dto.delivery.DeliveryResponse;
import com.sparta.logistics.infrastructure.feign.dto.delivery.DeliveryStatusResponse;
import com.sparta.logistics.infrastructure.feign.dto.hub.HubStockRequest;
import com.sparta.logistics.infrastructure.feign.dto.product.ProductResponse;
import com.sparta.logistics.common.code.ErrorResponseCode;
import com.sparta.logistics.presentation.common.dto.response.GeneralResponse;
import com.sparta.logistics.common.exception.ApiException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class OrderExternalService {

    private final ProductClient productClient;
    private final HubClient hubClient;
    private final DeliveryClient deliveryClient;

    // Product
    @CircuitBreaker(name = "productService", fallbackMethod = "fallbackProduct")
    public GeneralResponse<ProductResponse> getProduct(UUID productId) {
        return productClient.getProduct(productId);
    }

    private GeneralResponse<ProductResponse> fallbackProduct(UUID productId, Throwable cause) {
        log.error(
                "외부 API 대체 처리 실행. 대상서비스=상품 서비스, 작업=상품 조회, 상품ID={}",
                productId,
                cause
        );
        throw new ApiException(ErrorResponseCode.ORDER_PRODUCT_LOOKUP_FAILED, cause);
    }

    // Hub
    @CircuitBreaker(name = "hubService", fallbackMethod = "fallbackDecreaseStock")
    public GeneralResponse<Void> decreaseStock(HubStockRequest request) {
        return hubClient.decreaseStock(request);
    }

    private GeneralResponse<Void> fallbackDecreaseStock(HubStockRequest request, Throwable cause) {
        log.error(
                "외부 API 대체 처리 실행. 대상서비스=허브 서비스, 작업=재고 차감, "
                        + "주문ID={}, 상품ID={}, 허브ID={}, 수량={}",
                request.orderId(),
                request.productId(),
                request.hubId(),
                request.quantity(),
                cause
        );
        throw new ApiException(ErrorResponseCode.ORDER_STOCK_DECREASE_FAILED, cause);
    }

    @CircuitBreaker(name = "hubService", fallbackMethod = "fallbackIncreaseStock")
    public GeneralResponse<Void> increaseStock(HubStockRequest request) {
        return hubClient.increaseStock(request);
    }

    private GeneralResponse<Void> fallbackIncreaseStock(HubStockRequest request, Throwable cause) {
        log.error(
                "외부 API 대체 처리 실행. 대상서비스=허브 서비스, 작업=재고 복원, "
                        + "주문ID={}, 상품ID={}, 허브ID={}, 수량={}",
                request.orderId(),
                request.productId(),
                request.hubId(),
                request.quantity(),
                cause
        );
        throw new ApiException(ErrorResponseCode.ORDER_STOCK_RESTORE_FAILED, cause);
    }

    // Delivery
    @CircuitBreaker(name = "deliveryService", fallbackMethod = "fallbackCreateDelivery")
    public GeneralResponse<DeliveryResponse> createDelivery(CreateDeliveryRequest request) {
        return deliveryClient.createDelivery(request);
    }

    private GeneralResponse<DeliveryResponse> fallbackCreateDelivery(
            CreateDeliveryRequest request,
            Throwable cause
    ) {
        log.error(
                "외부 API 대체 처리 실행. 대상서비스=배송 서비스, 작업=배송 생성, "
                        + "주문ID={}, 출발허브ID={}, 도착허브ID={}",
                request.orderId(),
                request.departureHubId(),
                request.destinationHubId(),
                cause
        );
        throw new ApiException(ErrorResponseCode.ORDER_DELIVERY_CREATE_FAILED, cause);
    }

    @CircuitBreaker(name = "deliveryService", fallbackMethod = "fallbackCancelDelivery")
    public GeneralResponse<Void> cancelDelivery(UUID deliveryId, CancelDeliveryRequest request) {
        return deliveryClient.cancelDelivery(deliveryId, request);
    }

    private GeneralResponse<Void> fallbackCancelDelivery(
            UUID deliveryId,
            CancelDeliveryRequest request,
            Throwable cause
    ) {
        log.error(
                "외부 API 대체 처리 실행. 대상서비스=배송 서비스, 작업=배송 취소, "
                        + "주문ID={}, 배송ID={}",
                request.orderId(),
                deliveryId,
                cause
        );
        throw new ApiException(ErrorResponseCode.ORDER_DELIVERY_CANCEL_FAILED, cause);
    }

    @CircuitBreaker(name = "deliveryService", fallbackMethod = "fallbackGetDeliveryStatus")
    public GeneralResponse<DeliveryStatusResponse> getDeliveryStatus(UUID deliveryId) {
        return deliveryClient.getDeliveryStatus(deliveryId);
    }

    private GeneralResponse<DeliveryStatusResponse> fallbackGetDeliveryStatus(
            UUID deliveryId,
            Throwable cause
    ) {
        log.error(
                "외부 API 대체 처리 실행. 대상서비스=배송 서비스, 작업=배송 상태 조회, 배송ID={}",
                deliveryId,
                cause
        );
        throw new ApiException(ErrorResponseCode.ORDER_DELIVERY_STATUS_LOOKUP_FAILED, cause);
    }

}
