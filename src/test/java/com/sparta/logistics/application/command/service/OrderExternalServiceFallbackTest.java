package com.sparta.logistics.application.command.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.sparta.logistics.common.code.ErrorResponseCode;
import com.sparta.logistics.common.exception.ApiException;
import com.sparta.logistics.infrastructure.feign.client.DeliveryClient;
import com.sparta.logistics.infrastructure.feign.client.HubClient;
import com.sparta.logistics.infrastructure.feign.client.ProductClient;
import com.sparta.logistics.infrastructure.feign.dto.delivery.CancelDeliveryRequest;
import com.sparta.logistics.infrastructure.feign.dto.delivery.CreateDeliveryRequest;
import com.sparta.logistics.infrastructure.feign.dto.hub.HubStockRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

@ExtendWith(MockitoExtension.class)
class OrderExternalServiceFallbackTest {

    @Mock
    private ProductClient productClient;

    @Mock
    private HubClient hubClient;

    @Mock
    private DeliveryClient deliveryClient;

    @InjectMocks
    private OrderExternalService orderExternalService;

    private Logger logger;
    private ListAppender<ILoggingEvent> logAppender;

    @BeforeEach
    void setUpLogAppender() {
        logger = (Logger) LoggerFactory.getLogger(OrderExternalService.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        logger.addAppender(logAppender);
    }

    @AfterEach
    void tearDownLogAppender() {
        logger.detachAppender(logAppender);
        logAppender.stop();
    }

    @Test
    @DisplayName("상품 조회 fallback은 대상과 작업을 기록하고 원인 예외를 보존한다")
    void fallbackProduct_logsCauseAndPreservesExceptionChain() {
        UUID productId = UUID.randomUUID();
        RuntimeException cause = new RuntimeException("상품 서비스 연결 실패");

        ApiException exception = invokeFallback("fallbackProduct", productId, cause);

        assertFallback(
                exception,
                ErrorResponseCode.ORDER_PRODUCT_LOOKUP_FAILED,
                cause,
                "대상서비스=상품 서비스",
                "작업=상품 조회",
                "상품ID=" + productId
        );
    }

    @Test
    @DisplayName("재고 차감 fallback은 요청 식별자와 원인 예외를 기록한다")
    void fallbackDecreaseStock_logsIdentifiersAndPreservesExceptionChain() {
        HubStockRequest request = createStockRequest();
        RuntimeException cause = new RuntimeException("재고 차감 시간 초과");

        ApiException exception = invokeFallback("fallbackDecreaseStock", request, cause);

        assertFallback(
                exception,
                ErrorResponseCode.ORDER_STOCK_DECREASE_FAILED,
                cause,
                "대상서비스=허브 서비스",
                "작업=재고 차감",
                "주문ID=" + request.orderId(),
                "상품ID=" + request.productId(),
                "허브ID=" + request.hubId(),
                "수량=" + request.quantity()
        );
    }

    @Test
    @DisplayName("재고 복원 fallback은 요청 식별자와 원인 예외를 기록한다")
    void fallbackIncreaseStock_logsIdentifiersAndPreservesExceptionChain() {
        HubStockRequest request = createStockRequest();
        RuntimeException cause = new RuntimeException("재고 복원 시간 초과");

        ApiException exception = invokeFallback("fallbackIncreaseStock", request, cause);

        assertFallback(
                exception,
                ErrorResponseCode.ORDER_STOCK_RESTORE_FAILED,
                cause,
                "대상서비스=허브 서비스",
                "작업=재고 복원",
                "주문ID=" + request.orderId(),
                "상품ID=" + request.productId(),
                "허브ID=" + request.hubId(),
                "수량=" + request.quantity()
        );
    }

    @Test
    @DisplayName("배송 생성 fallback은 개인정보를 제외하고 요청 식별자와 원인 예외를 기록한다")
    void fallbackCreateDelivery_excludesPersonalInformationAndPreservesExceptionChain() {
        CreateDeliveryRequest request = new CreateDeliveryRequest(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "private-address",
                "private-name",
                "private-slack-id"
        );
        RuntimeException cause = new RuntimeException("배송 생성 실패");

        ApiException exception = invokeFallback("fallbackCreateDelivery", request, cause);

        assertFallback(
                exception,
                ErrorResponseCode.ORDER_DELIVERY_CREATE_FAILED,
                cause,
                "대상서비스=배송 서비스",
                "작업=배송 생성",
                "주문ID=" + request.orderId(),
                "출발허브ID=" + request.departureHubId(),
                "도착허브ID=" + request.destinationHubId()
        );
        assertThat(singleLog().getFormattedMessage())
                .doesNotContain(request.deliveryAddress())
                .doesNotContain(request.receiverName())
                .doesNotContain(request.receiverSlackId());
    }

    @Test
    @DisplayName("배송 취소 fallback은 취소 사유를 제외하고 요청 식별자와 원인 예외를 기록한다")
    void fallbackCancelDelivery_excludesReasonAndPreservesExceptionChain() {
        UUID deliveryId = UUID.randomUUID();
        CancelDeliveryRequest request = new CancelDeliveryRequest(
                UUID.randomUUID(),
                "private-cancel-reason"
        );
        RuntimeException cause = new RuntimeException("배송 취소 실패");

        ApiException exception = invokeFallback("fallbackCancelDelivery", deliveryId, request, cause);

        assertFallback(
                exception,
                ErrorResponseCode.ORDER_DELIVERY_CANCEL_FAILED,
                cause,
                "대상서비스=배송 서비스",
                "작업=배송 취소",
                "주문ID=" + request.orderId(),
                "배송ID=" + deliveryId
        );
        assertThat(singleLog().getFormattedMessage()).doesNotContain(request.canceledReason());
    }

    @Test
    @DisplayName("배송 상태 조회 fallback은 대상과 작업을 기록하고 원인 예외를 보존한다")
    void fallbackGetDeliveryStatus_logsCauseAndPreservesExceptionChain() {
        UUID deliveryId = UUID.randomUUID();
        RuntimeException cause = new RuntimeException("배송 상태 조회 실패");

        ApiException exception = invokeFallback("fallbackGetDeliveryStatus", deliveryId, cause);

        assertFallback(
                exception,
                ErrorResponseCode.ORDER_DELIVERY_STATUS_LOOKUP_FAILED,
                cause,
                "대상서비스=배송 서비스",
                "작업=배송 상태 조회",
                "배송ID=" + deliveryId
        );
    }

    private HubStockRequest createStockRequest() {
        return new HubStockRequest(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                3
        );
    }

    private ApiException invokeFallback(String methodName, Object... arguments) {
        return catchThrowableOfType(
                ApiException.class,
                () -> ReflectionTestUtils.invokeMethod(orderExternalService, methodName, arguments)
        );
    }

    private void assertFallback(
            ApiException exception,
            ErrorResponseCode responseCode,
            Throwable cause,
            String... expectedLogFragments
    ) {
        assertThat(exception).isNotNull();
        assertThat(exception.getResponseCode()).isEqualTo(responseCode);
        assertThat(exception.getCause()).isSameAs(cause);

        ILoggingEvent logEvent = singleLog();
        assertThat(logEvent.getLevel()).isEqualTo(Level.ERROR);
        assertThat(logEvent.getThrowableProxy()).isNotNull();
        assertThat(logEvent.getThrowableProxy().getMessage()).isEqualTo(cause.getMessage());
        assertThat(logEvent.getFormattedMessage()).contains(expectedLogFragments);
    }

    private ILoggingEvent singleLog() {
        assertThat(logAppender.list).hasSize(1);
        return logAppender.list.get(0);
    }
}
