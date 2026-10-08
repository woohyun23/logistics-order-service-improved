package com.sparta.logistics.infrastructure.messaging.listener;

import com.sparta.logistics.common.metrics.OrderPerformanceMetrics;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class OrderSagaEventListener {

    private final OrderSagaEventProcessor orderSagaEventProcessor;
    private final OrderPerformanceMetrics performanceMetrics;

    @RabbitListener(queues = "${message.queue.order:order.queue}")
    public void listen(String message) throws Exception {
        Timer.Sample sample = performanceMetrics.startTimer();
        try {
            SagaEventProcessingResult result = orderSagaEventProcessor.process(message);
            String resultTag = result.name().toLowerCase();
            performanceMetrics.recordConsumerEvent(resultTag);
            performanceMetrics.stopConsumerTimer(sample);
        } catch (Exception e) {
            performanceMetrics.recordConsumerEvent("failed");
            performanceMetrics.stopConsumerTimer(sample);
            throw e;
        }
    }
}
