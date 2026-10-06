package com.sparta.logistics.infrastructure.messaging.listener;

import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class OrderSagaEventListener {

    private final OrderSagaEventProcessor orderSagaEventProcessor;

    @RabbitListener(queues = "${message.queue.order:order.queue}")
    public void listen(String message) throws Exception {
        orderSagaEventProcessor.process(message);
    }
}
