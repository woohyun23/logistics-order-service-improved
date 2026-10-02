package com.sparta.logistics.infrastructure.messaging.outbox;

import com.sparta.logistics.application.command.service.OutboxEventPublisher;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class OutboxEventScheduler {

    private final OutboxEventPublisher outboxEventPublisher;

    // 정해진 시간에 발행 작업을 시작하는 인프라 진입점
    @Scheduled(fixedDelayString = "${message.outbox.publish-delay-ms:5000}")
    public void publishPendingEvents() {
        outboxEventPublisher.publishPendingEvents();
    }
}
