package com.sparta.logistics.application.command.service;

import com.sparta.logistics.domain.entity.OutboxEvent;
import com.sparta.logistics.domain.model.OutboxStatus;
import com.sparta.logistics.domain.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.List;

@Service
@Slf4j
@RequiredArgsConstructor
public class OutboxEventPublisher {

    private static final int MAX_RETRY_COUNT = 3;

    private final OutboxEventRepository outboxEventRepository;
    private final RabbitTemplate rabbitTemplate;

    // 트랜잭션 안에서 이벤트를 조회하고 발행 상태를 변경하는 애플리케이션 서비스
    @Transactional
    public int publishPendingEvents() {
        List<OutboxEvent> events =
                outboxEventRepository.findTop50ByStatusOrderByCreatedAtAsc(OutboxStatus.PENDING);

        events.forEach(this::publish);
        return events.size();
    }

    private void publish(OutboxEvent event) {
        try {
            Message message = MessageBuilder
                    .withBody(event.getPayload().getBytes(StandardCharsets.UTF_8))
                    .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                    .build();

            rabbitTemplate.send(event.getExchange(), event.getRoutingKey(), message);
            event.markPublished();
        } catch (Exception e) {
            log.error("Outbox event publish failed. event={}", event, e);

            event.markPublishFailed(e.getMessage(), MAX_RETRY_COUNT);
        }
    }
}
