package com.sparta.logistics.infrastructure.messaging.listener;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparta.logistics.application.command.service.OrderSagaService;
import com.sparta.logistics.domain.repository.ProcessedEventRepository;
import com.sparta.logistics.infrastructure.messaging.event.delivery.DeliveryCancelFailedPayload;
import com.sparta.logistics.infrastructure.messaging.event.delivery.DeliveryCanceledPayload;
import com.sparta.logistics.infrastructure.messaging.event.delivery.DeliveryCreateFailedPayload;
import com.sparta.logistics.infrastructure.messaging.event.delivery.DeliveryCreatedPayload;
import com.sparta.logistics.infrastructure.messaging.event.hub.InventoryDeductFailedPayload;
import com.sparta.logistics.infrastructure.messaging.event.hub.InventoryDeductedPayload;
import com.sparta.logistics.infrastructure.messaging.event.hub.InventoryRestoreFailedPayload;
import com.sparta.logistics.infrastructure.messaging.event.hub.InventoryRestoredPayload;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

import static com.sparta.logistics.infrastructure.messaging.event.order.OrderEventConstants.DELIVERY_CANCELED_EVENT;
import static com.sparta.logistics.infrastructure.messaging.event.order.OrderEventConstants.DELIVERY_CANCEL_FAILED_EVENT;
import static com.sparta.logistics.infrastructure.messaging.event.order.OrderEventConstants.DELIVERY_CREATED_EVENT;
import static com.sparta.logistics.infrastructure.messaging.event.order.OrderEventConstants.DELIVERY_CREATE_FAILED_EVENT;
import static com.sparta.logistics.infrastructure.messaging.event.order.OrderEventConstants.INVENTORY_DEDUCTED_EVENT;
import static com.sparta.logistics.infrastructure.messaging.event.order.OrderEventConstants.INVENTORY_DEDUCT_FAILED_EVENT;
import static com.sparta.logistics.infrastructure.messaging.event.order.OrderEventConstants.INVENTORY_RESTORED_EVENT;
import static com.sparta.logistics.infrastructure.messaging.event.order.OrderEventConstants.INVENTORY_RESTORE_FAILED_EVENT;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderSagaEventProcessor {

    private static final String CONSUMER_NAME = "order-saga-listener";

    private final ObjectMapper objectMapper;
    private final ProcessedEventRepository processedEventRepository;
    private final OrderSagaService orderSagaService;

    @Transactional
    public SagaEventProcessingResult process(String message) throws JsonProcessingException {
        JsonNode root = objectMapper.readTree(message);
        JsonNode header = root.path("header");
        String eventId = requiredText(header, "messageId");
        String eventType = requiredText(header, "eventType");

        int claimed = processedEventRepository.claim(
                eventId,
                eventType,
                CONSUMER_NAME,
                Instant.now()
        );

        if (claimed == 0) {
            log.info("이미 처리된 Saga 이벤트를 건너뜁니다. eventId={}, eventType={}, consumer={}",
                    eventId, eventType, CONSUMER_NAME);
            return SagaEventProcessingResult.DUPLICATE;
        }

        dispatch(eventId, eventType, root.path("payload"));
        return SagaEventProcessingResult.PROCESSED;
    }

    private void dispatch(String eventId, String eventType, JsonNode payload) throws JsonProcessingException {
        switch (eventType) {
            case INVENTORY_DEDUCTED_EVENT -> {
                InventoryDeductedPayload event = objectMapper.treeToValue(payload, InventoryDeductedPayload.class);
                orderSagaService.handleInventoryDeducted(event.orderId());
            }
            case INVENTORY_DEDUCT_FAILED_EVENT -> {
                InventoryDeductFailedPayload event = objectMapper.treeToValue(payload, InventoryDeductFailedPayload.class);
                orderSagaService.handleInventoryDeductFailed(event.orderId(), event.reason());
            }
            case INVENTORY_RESTORED_EVENT -> {
                InventoryRestoredPayload event = objectMapper.treeToValue(payload, InventoryRestoredPayload.class);
                orderSagaService.handleInventoryRestored(event.orderId());
            }
            case INVENTORY_RESTORE_FAILED_EVENT -> {
                InventoryRestoreFailedPayload event = objectMapper.treeToValue(payload, InventoryRestoreFailedPayload.class);
                orderSagaService.handleInventoryRestoreFailed(event.orderId(), event.reason());
            }
            case DELIVERY_CREATED_EVENT -> {
                DeliveryCreatedPayload event = objectMapper.treeToValue(payload, DeliveryCreatedPayload.class);
                orderSagaService.handleDeliveryCreated(event.orderId(), event.deliveryId());
            }
            case DELIVERY_CREATE_FAILED_EVENT -> {
                DeliveryCreateFailedPayload event = objectMapper.treeToValue(payload, DeliveryCreateFailedPayload.class);
                orderSagaService.handleDeliveryCreateFailed(event.orderId(), event.reason());
            }
            case DELIVERY_CANCELED_EVENT -> {
                DeliveryCanceledPayload event = objectMapper.treeToValue(payload, DeliveryCanceledPayload.class);
                orderSagaService.handleDeliveryCanceled(event.orderId());
            }
            case DELIVERY_CANCEL_FAILED_EVENT -> {
                DeliveryCancelFailedPayload event = objectMapper.treeToValue(payload, DeliveryCancelFailedPayload.class);
                orderSagaService.handleDeliveryCancelFailed(event.orderId(), event.reason());
            }
            default -> log.warn("지원하지 않는 Saga 이벤트 유형입니다. eventId={}, eventType={}", eventId, eventType);
        }
    }

    private String requiredText(JsonNode node, String fieldName) {
        JsonNode valueNode = node.path(fieldName);
        if (!valueNode.isTextual() || valueNode.asText().isBlank()) {
            throw new IllegalArgumentException("이벤트 헤더의 " + fieldName + " 값이 필요합니다.");
        }
        return valueNode.asText();
    }
}
