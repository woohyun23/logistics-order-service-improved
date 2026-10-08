package com.sparta.logistics.infrastructure.messaging.outbox;

import com.sparta.logistics.application.command.service.OutboxEventPublisher;
import com.sparta.logistics.common.metrics.OrderPerformanceMetrics;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class OutboxEventScheduler {

    private final OutboxEventPublisher outboxEventPublisher;
    private final OrderPerformanceMetrics performanceMetrics;

    // 정해진 시간에 발행 작업을 시작하는 인프라 진입점
    @Scheduled(fixedDelayString = "${message.outbox.publish-delay-ms:5000}")
    public void publishPendingEvents() {
        Timer.Sample sample = performanceMetrics.startTimer();
        try {
            outboxEventPublisher.publishPendingEvents();
            performanceMetrics.stopTimer(sample, "outbox_publish_batch", "success");
        } catch (RuntimeException e) {
            performanceMetrics.stopTimer(sample, "outbox_publish_batch", "failed");
            throw e;
        }
    }
}
