package com.sparta.logistics.application.query.dto;

import com.sparta.logistics.infrastructure.feign.dto.delivery.DeliveryStatusResponse;
import com.sparta.logistics.infrastructure.feign.dto.product.ProductResponse;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

@Schema(description = "주문 외부 서비스 정보 조회 응답")
public record OrderExternalInfoResponse(
        @Schema(description = "주문 ID")
        UUID orderId,

        @Schema(description = "상품 정보")
        ExternalLookupResponse<ProductResponse> product,

        @Schema(description = "배송 상태", nullable = true)
        ExternalLookupResponse<DeliveryStatusResponse> deliveryStatus
) {
}
