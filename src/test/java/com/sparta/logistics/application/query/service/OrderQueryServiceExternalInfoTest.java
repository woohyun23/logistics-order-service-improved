package com.sparta.logistics.application.query.service;

import com.sparta.logistics.application.query.dto.ExternalLookupResponse;
import com.sparta.logistics.application.query.dto.OrderExternalInfoResponse;
import com.sparta.logistics.domain.entity.Order;
import com.sparta.logistics.domain.repository.OrderRepository;
import com.sparta.logistics.infrastructure.feign.dto.product.ProductResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderQueryServiceExternalInfoTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private OrderExternalQueryService orderExternalQueryService;

    @InjectMocks
    private OrderQueryService orderQueryService;

    @Test
    @DisplayName("배송이 배정되지 않은 주문은 상품 외부 정보만 조회한다")
    void getOrderExternalInfo_withoutDelivery_returnsOnlyProduct() {
        UUID orderId = UUID.randomUUID();
        Order order = Order.create(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                3,
                "요청사항",
                Instant.now().plusSeconds(86_400)
        );
        ReflectionTestUtils.setField(order, "id", orderId);
        ProductResponse product = new ProductResponse(
                order.getProductId(),
                "테스트 상품",
                UUID.randomUUID(),
                "테스트 업체",
                Instant.now(),
                Instant.now()
        );
        ExternalLookupResponse<ProductResponse> productResponse =
                ExternalLookupResponse.live(product, Instant.now());
        when(orderRepository.findByIdAndDeletedAtIsNull(orderId)).thenReturn(Optional.of(order));
        when(orderExternalQueryService.getProduct(order.getProductId())).thenReturn(productResponse);

        OrderExternalInfoResponse response = orderQueryService.getOrderExternalInfo(orderId);

        assertThat(response.orderId()).isEqualTo(orderId);
        assertThat(response.product()).isEqualTo(productResponse);
        assertThat(response.deliveryStatus()).isNull();
        verify(orderExternalQueryService, never()).getDeliveryStatus(any());
    }
}
