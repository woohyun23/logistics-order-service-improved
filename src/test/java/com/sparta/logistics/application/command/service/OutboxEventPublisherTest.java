package com.sparta.logistics.application.command.service;

import com.sparta.logistics.domain.entity.OutboxEvent;
import com.sparta.logistics.domain.model.OutboxStatus;
import com.sparta.logistics.domain.repository.OutboxEventRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OutboxEventPublisherTest {

    @Mock
    private OutboxEventRepository outboxEventRepository;

    @Mock
    private RabbitTemplate rabbitTemplate;

    @InjectMocks
    private OutboxEventPublisher outboxEventPublisher;

    @Test
    @DisplayName("PENDING Outbox 이벤트를 RabbitMQ로 발행하고 PUBLISHED 상태로 변경한다")
    void publishPendingEvents_success_marksPublished() {
        OutboxEvent event = createOutboxEvent();

        when(outboxEventRepository.findPendingEventsForPublish())
                .thenReturn(List.of(event));

        int publishedCount = outboxEventPublisher.publishPendingEvents();

        ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);

        assertThat(publishedCount).isEqualTo(1);
        verify(rabbitTemplate).send(
                eq("baekma.exchange"),
                eq("order.created"),
                messageCaptor.capture()
        );

        Message message = messageCaptor.getValue();
        assertThat(new String(message.getBody(), StandardCharsets.UTF_8))
                .isEqualTo("{\"test\":\"payload\"}");
        assertThat(message.getMessageProperties().getContentType())
                .isEqualTo("application/json");

        assertThat(event.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
        assertThat(event.getPublishedAt()).isNotNull();
        assertThat(event.getErrorMessage()).isNull();
        assertThat(event.getRetryCount()).isZero();
    }

    @Test
    @DisplayName("Outbox 이벤트 발행 실패 시 retryCount와 errorMessage를 기록한다")
    void publishPendingEvents_failure_incrementsRetryCount() {
        OutboxEvent event = createOutboxEvent();

        when(outboxEventRepository.findPendingEventsForPublish())
                .thenReturn(List.of(event));
        doThrow(new RuntimeException("rabbit publish failed"))
                .when(rabbitTemplate)
                .send(eq("baekma.exchange"), eq("order.created"), org.mockito.ArgumentMatchers.any(Message.class));

        int publishedCount = outboxEventPublisher.publishPendingEvents();

        assertThat(publishedCount).isEqualTo(1);
        assertThat(event.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(event.getRetryCount()).isEqualTo(1);
        assertThat(event.getErrorMessage()).isEqualTo("rabbit publish failed");
        assertThat(event.getPublishedAt()).isNull();
    }

    @Test
    @DisplayName("Outbox 이벤트 발행 실패가 최대 재시도 횟수에 도달하면 FAILED 상태로 변경한다")
    void publishPendingEvents_failureOverMaxRetry_marksFailed() {
        OutboxEvent event = createOutboxEvent();

        when(outboxEventRepository.findPendingEventsForPublish())
                .thenReturn(List.of(event));
        doThrow(new RuntimeException("rabbit publish failed"))
                .when(rabbitTemplate)
                .send(eq("baekma.exchange"), eq("order.created"), org.mockito.ArgumentMatchers.any(Message.class));

        outboxEventPublisher.publishPendingEvents();
        outboxEventPublisher.publishPendingEvents();
        outboxEventPublisher.publishPendingEvents();

        verify(rabbitTemplate, times(3))
                .send(eq("baekma.exchange"), eq("order.created"), org.mockito.ArgumentMatchers.any(Message.class));

        assertThat(event.getStatus()).isEqualTo(OutboxStatus.FAILED);
        assertThat(event.getRetryCount()).isEqualTo(3);
        assertThat(event.getErrorMessage()).isEqualTo("rabbit publish failed");
        assertThat(event.getPublishedAt()).isNull();
    }

    @Test
    @DisplayName("Outbox 이벤트 재발행 시 최초 payload의 이벤트 ID를 그대로 사용한다")
    void publishPendingEvents_retry_preservesEventId() {
        String eventId = UUID.randomUUID().toString();
        String payload = "{\"header\":{\"messageId\":\"" + eventId + "\"}}";
        OutboxEvent event = createOutboxEvent(payload);

        when(outboxEventRepository.findPendingEventsForPublish())
                .thenReturn(List.of(event));
        doThrow(new RuntimeException("rabbit publish failed"))
                .doNothing()
                .when(rabbitTemplate)
                .send(eq("baekma.exchange"), eq("order.created"), org.mockito.ArgumentMatchers.any(Message.class));

        outboxEventPublisher.publishPendingEvents();
        outboxEventPublisher.publishPendingEvents();

        ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
        verify(rabbitTemplate, times(2))
                .send(eq("baekma.exchange"), eq("order.created"), messageCaptor.capture());

        assertThat(messageCaptor.getAllValues())
                .extracting(message -> new String(message.getBody(), StandardCharsets.UTF_8))
                .containsExactly(payload, payload);
    }

    private OutboxEvent createOutboxEvent() {
        return createOutboxEvent("{\"test\":\"payload\"}");
    }

    private OutboxEvent createOutboxEvent(String payload) {
        return OutboxEvent.create(
                "ORDER",
                UUID.randomUUID(),
                "OrderCreatedEvent",
                "baekma.exchange",
                "order.created",
                payload
        );
    }
}
