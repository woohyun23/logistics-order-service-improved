package com.sparta.logistics.presentation.query.controller;

import com.sparta.logistics.application.query.dto.OrderDetailResponse;
import com.sparta.logistics.application.query.dto.OrderExternalInfoResponse;
import com.sparta.logistics.application.query.dto.OrderSearchCondition;
import com.sparta.logistics.application.query.dto.OrderSearchResponse;
import com.sparta.logistics.application.query.dto.OrderStatsResponse;
import com.sparta.logistics.application.query.usecase.OrderQueryUseCase;
import com.sparta.logistics.domain.model.OrderStatus;
import com.sparta.logistics.presentation.common.dto.response.GeneralResponse;
import com.sparta.logistics.common.code.GeneralResponseCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
@Tag(name = "Order Query", description = "주문 조회, 검색, 통계 API")
@RequestMapping("/api/v1/orders")
public class OrderQueryController {

    private final OrderQueryUseCase orderQueryUseCase;

    @Operation(summary = "주문 상세 조회", description = "주문 ID로 주문 상세 정보를 조회합니다.")
    @GetMapping("/{orderId}")
    public ResponseEntity<GeneralResponse<OrderDetailResponse>> getOrderDetail(
            @PathVariable UUID orderId
    ) {
        OrderDetailResponse response = orderQueryUseCase.getOrderDetail(orderId);

        return GeneralResponse.toResponseEntity(GeneralResponseCode.OK, response);
    }

    @Operation(
            summary = "주문 외부 정보 조회",
            description = "상품 정보와 배송 상태를 조회하며 외부 서비스 장애 시 최근 캐시 데이터를 반환합니다."
    )
    @GetMapping("/{orderId}/external-info")
    public ResponseEntity<GeneralResponse<OrderExternalInfoResponse>> getOrderExternalInfo(
            @PathVariable UUID orderId
    ) {
        OrderExternalInfoResponse response = orderQueryUseCase.getOrderExternalInfo(orderId);

        return GeneralResponse.toResponseEntity(GeneralResponseCode.OK, response);
    }

    @Operation(summary = "주문 목록 검색", description = "수령 업체, 상품, 배송, 상태, 기간 조건으로 주문 목록을 검색합니다.")
    @GetMapping
    public ResponseEntity<GeneralResponse<Page<OrderSearchResponse>>> searchOrders(
            @RequestParam(required = false) UUID receiverCompanyId,
            @RequestParam(required = false) UUID productId,
            @RequestParam(required = false) UUID deliveryId,
            @RequestParam(required = false) OrderStatus status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant endDate,
            @PageableDefault(size = 10, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable
    ) {
        OrderSearchCondition condition = new OrderSearchCondition(
                receiverCompanyId,
                productId,
                deliveryId,
                status,
                startDate,
                endDate
        );

        Page<OrderSearchResponse> response = orderQueryUseCase.searchOrder(condition, pageable);

        return GeneralResponse.toResponseEntity(GeneralResponseCode.OK, response);
    }

    @Operation(summary = "주문 통계 조회", description = "조건에 따른 주문 상태별, 업체별, 일자별 통계를 조회합니다.")
    @GetMapping("/stats")
    public ResponseEntity<GeneralResponse<OrderStatsResponse >> getOrderStats(
            @RequestParam(required = false) UUID receiverCompanyId,
            @RequestParam(required = false) UUID productId,
            @RequestParam(required = false) UUID deliveryId,
            @RequestParam(required = false) OrderStatus status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant endDate
    ) {
        OrderSearchCondition condition = new OrderSearchCondition(
                receiverCompanyId,
                productId,
                deliveryId,
                status,
                startDate,
                endDate
        );

        OrderStatsResponse response = orderQueryUseCase.getOrderStats(condition);

        return GeneralResponse.toResponseEntity(GeneralResponseCode.OK, response);
    }
}
