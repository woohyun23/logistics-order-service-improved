package com.sparta.logistics.infrastructure.messaging.outbox;

import com.sparta.logistics.application.command.service.OutboxEventPublisher;
import com.sparta.logistics.common.metrics.OrderPerformanceMetrics;
import io.micrometer.core.instrument.Timer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OutboxEventSchedulerTest {

    @Mock
    private OutboxEventPublisher outboxEventPublisher;

    @Mock
    private OrderPerformanceMetrics performanceMetrics;

    @Mock
    private Timer.Sample timerSample;

    @InjectMocks
    private OutboxEventScheduler outboxEventScheduler;

    @Test
    @DisplayName("스케줄 실행 시 Outbox 이벤트 발행 서비스에 처리를 위임한다")
    void publishPendingEvents_delegatesToPublisher() {
        when(performanceMetrics.startTimer()).thenReturn(timerSample);

        outboxEventScheduler.publishPendingEvents();

        verify(outboxEventPublisher).publishPendingEvents();
        verify(performanceMetrics).stopTimer(timerSample, "outbox_publish_batch", "success");
    }
}
