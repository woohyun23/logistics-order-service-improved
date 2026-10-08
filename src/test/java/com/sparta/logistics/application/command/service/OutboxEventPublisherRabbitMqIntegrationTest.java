package com.sparta.logistics.application.command.service;

import com.sparta.logistics.domain.entity.OutboxEvent;
import com.sparta.logistics.common.metrics.OrderPerformanceMetrics;
import com.sparta.logistics.domain.model.OutboxStatus;
import com.sparta.logistics.domain.repository.OutboxEventRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@Testcontainers
@ExtendWith(MockitoExtension.class)
class OutboxEventPublisherRabbitMqIntegrationTest {

    private static final String RABBITMQ_USER = "guest";
    private static final String RABBITMQ_PASSWORD = "guest";
    private static final String EXCHANGE_NAME = "baekma.exchange";
    private static final String ROUTING_KEY = "order.created";

    @Container
    static GenericContainer<?> rabbitMq = new GenericContainer<>(
            DockerImageName.parse("rabbitmq:3.13-management-alpine")
    )
            .withExposedPorts(5672)
            .withEnv("RABBITMQ_DEFAULT_USER", RABBITMQ_USER)
            .withEnv("RABBITMQ_DEFAULT_PASS", RABBITMQ_PASSWORD);

    @Mock
    private OutboxEventRepository outboxEventRepository;

    private CachingConnectionFactory connectionFactory;
    private RabbitTemplate rabbitTemplate;
    private OutboxEventPublisher outboxEventPublisher;
    private String queueName;

    @BeforeEach
    void setUp() {
        connectionFactory = new CachingConnectionFactory(rabbitMq.getHost(), rabbitMq.getMappedPort(5672));
        connectionFactory.setUsername(RABBITMQ_USER);
        connectionFactory.setPassword(RABBITMQ_PASSWORD);

        rabbitTemplate = new RabbitTemplate(connectionFactory);
        outboxEventPublisher = new OutboxEventPublisher(
                outboxEventRepository,
                rabbitTemplate,
                new OrderPerformanceMetrics(new SimpleMeterRegistry())
        );

        RabbitAdmin rabbitAdmin = new RabbitAdmin(connectionFactory);
        TopicExchange exchange = new TopicExchange(EXCHANGE_NAME);
        Queue queue = new Queue("order.integration.queue." + UUID.randomUUID(), false, false, true);
        queueName = queue.getName();

        rabbitAdmin.declareExchange(exchange);
        rabbitAdmin.declareQueue(queue);
        rabbitAdmin.declareBinding(BindingBuilder.bind(queue).to(exchange).with(ROUTING_KEY));
    }

    @AfterEach
    void tearDown() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    @Test
    @DisplayName("Outbox 이벤트를 실제 RabbitMQ exchange로 발행하면 바인딩된 queue에서 메시지를 수신할 수 있다")
    void publishPendingEvents_sendsMessageToRabbitMqQueue() {
        String payload = "{\"event\":\"OrderCreatedEvent\"}";
        OutboxEvent event = OutboxEvent.create(
                "ORDER",
                UUID.randomUUID(),
                "OrderCreatedEvent",
                EXCHANGE_NAME,
                ROUTING_KEY,
                payload
        );

        when(outboxEventRepository.findPendingEventsForPublish())
                .thenReturn(List.of(event));

        int publishedCount = outboxEventPublisher.publishPendingEvents();

        Message receivedMessage = rabbitTemplate.receive(queueName, 3000);

        assertThat(publishedCount).isEqualTo(1);
        assertThat(receivedMessage).isNotNull();
        assertThat(new String(receivedMessage.getBody(), StandardCharsets.UTF_8))
                .isEqualTo(payload);
        assertThat(event.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
        assertThat(event.getPublishedAt()).isNotNull();
    }
}
