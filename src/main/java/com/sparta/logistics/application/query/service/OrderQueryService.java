package com.sparta.logistics.application.query.service;

import com.sparta.logistics.application.query.dto.InternalOrderResponse;
import com.sparta.logistics.application.query.dto.OrderDetailResponse;
import com.sparta.logistics.application.query.dto.OrderExternalInfoResponse;
import com.sparta.logistics.application.query.dto.OrderSearchCondition;
import com.sparta.logistics.application.query.dto.OrderSearchResponse;
import com.sparta.logistics.application.query.dto.OrderStatsResponse;
import com.sparta.logistics.application.query.usecase.OrderQueryUseCase;
import com.sparta.logistics.domain.entity.Order;
import com.sparta.logistics.domain.model.OrderStatus;
import com.sparta.logistics.domain.repository.OrderRepository;
import com.sparta.logistics.common.code.ErrorResponseCode;
import com.sparta.logistics.common.exception.ApiException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OrderQueryService implements OrderQueryUseCase {

    private final OrderRepository orderRepository;
    private final OrderExternalQueryService orderExternalQueryService;

    @Override
    public OrderDetailResponse getOrderDetail(UUID orderId) {
        Order order = orderRepository.findByIdAndDeletedAtIsNull(orderId)
                .orElseThrow(() -> new ApiException(ErrorResponseCode.ORDER_NOT_FOUND));

        return OrderDetailResponse.from(order);
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public OrderExternalInfoResponse getOrderExternalInfo(UUID orderId) {
        Order order = orderRepository.findByIdAndDeletedAtIsNull(orderId)
                .orElseThrow(() -> new ApiException(ErrorResponseCode.ORDER_NOT_FOUND));

        var product = orderExternalQueryService.getProduct(order.getProductId());
        var deliveryStatus = order.getDeliveryId() == null
                ? null
                : orderExternalQueryService.getDeliveryStatus(order.getDeliveryId());

        return new OrderExternalInfoResponse(order.getId(), product, deliveryStatus);
    }

    @Override
    public Page<OrderSearchResponse> searchOrder(OrderSearchCondition condition, Pageable pageable) {
        return orderRepository.findAll(buildSpecification(condition), pageable)
                .map(OrderSearchResponse::from);
    }

    private Specification<Order> notDeleted() {
        return (root, query, criteriaBuilder)
                -> criteriaBuilder.isNull(root.get("deletedAt"));
    }

    private Specification<Order> receiverCompanyIdEquals(UUID receiverCompanyId) {
        return (root, query, criteriaBuilder) -> receiverCompanyId == null
                ? criteriaBuilder.conjunction()
                : criteriaBuilder.equal(root.get("receiverCompanyId"), receiverCompanyId);
    }

    private Specification<Order> productIdEquals(UUID productId) {
        return (root, query, criteriaBuilder) -> productId == null
                ? criteriaBuilder.conjunction()
                : criteriaBuilder.equal(root.get("productId"), productId);

    }

    private Specification<Order> deliveryIdEquals(UUID deliveryId) {
        return (root, query, criteriaBuilder) -> deliveryId == null
                ? criteriaBuilder.conjunction()
                : criteriaBuilder.equal(root.get("deliveryId"), deliveryId);
    }

    private Specification<Order> statusEquals(OrderStatus status) {
        return (root, query, criteriaBuilder) -> status == null
                ? criteriaBuilder.conjunction()
                : criteriaBuilder.equal(root.get("status"), status);

    }

    private Specification<Order> dueDateGreaterThanOrEqual(Instant startDate) {
        return (root, query, criteriaBuilder) -> startDate == null
                ? criteriaBuilder.conjunction()
                : criteriaBuilder.greaterThanOrEqualTo(root.get("dueDate"), startDate);

    }

    private Specification<Order> dueDateLessThanOrEqual(Instant endDate) {
        return (root, query, criteriaBuilder) -> endDate == null
                ? criteriaBuilder.conjunction()
                : criteriaBuilder.lessThanOrEqualTo(root.get("dueDate"), endDate);

    }


    @Override
    public OrderStatsResponse getOrderStats(OrderSearchCondition condition) {
        List<Order> orders = orderRepository.findAll(buildSpecification(condition));

        Map<OrderStatus, Long> statusCount = orders.stream()
                .collect(Collectors.groupingBy(Order::getStatus, Collectors.counting()));

        Map<UUID, Long> receiverCompanyCount = orders.stream()
                .collect(Collectors.groupingBy(Order::getReceiverCompanyId, Collectors.counting()));

        Map<LocalDate, Long> dailyCounts = orders.stream()
                .collect(Collectors.groupingBy(
                        order -> LocalDate.ofInstant(order.getDueDate(), ZoneId.of("Asia/Seoul")),
                        TreeMap::new,
                        Collectors.counting()
                ));

        return new OrderStatsResponse(
                orders.size(),
                statusCount,
                receiverCompanyCount,
                dailyCounts
        );
    }

    @Override
    public InternalOrderResponse getInternalOrder(UUID orderId) {
        Order order = orderRepository.findByIdAndDeletedAtIsNull(orderId)
                .orElseThrow(() -> new ApiException(ErrorResponseCode.ORDER_NOT_FOUND));

        return InternalOrderResponse.from(order);
    }

    private Specification<Order> buildSpecification(OrderSearchCondition condition) {
        return notDeleted()
                .and(receiverCompanyIdEquals(condition.receiverCompanyId()))
                .and(productIdEquals(condition.productId()))
                .and(deliveryIdEquals(condition.deliveryId()))
                .and(statusEquals(condition.status()))
                .and(dueDateGreaterThanOrEqual(condition.startDate()))
                .and(dueDateLessThanOrEqual(condition.endDate()));
    }
}
