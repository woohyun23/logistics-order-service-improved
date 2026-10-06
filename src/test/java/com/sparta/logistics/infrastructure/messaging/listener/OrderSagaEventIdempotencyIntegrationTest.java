package com.sparta.logistics.infrastructure.messaging.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.sparta.logistics.application.command.service.OrderSagaService;
import com.sparta.logistics.common.exception.ApiException;
import com.sparta.logistics.domain.entity.Order;
import com.sparta.logistics.domain.repository.OrderRepository;
import com.sparta.logistics.domain.repository.ProcessedEventRepository;
import com.sparta.logistics.infrastructure.messaging.envelope.EventEnvelope;
import com.sparta.logistics.infrastructure.messaging.envelope.EventHeader;
import com.sparta.logistics.infrastructure.messaging.event.delivery.DeliveryCreatedPayload;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.sparta.logistics.infrastructure.messaging.event.order.OrderEventConstants.DELIVERY_CREATED_EVENT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@DataJpaTest
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@ContextConfiguration(classes = {
        OrderSagaEventListener.class,
        OrderSagaEventProcessor.class,
        OrderSagaService.class,
        OrderSagaEventIdempotencyIntegrationTest.TestObjectMapperConfig.class,
        OrderSagaEventIdempotencyIntegrationTest.TestJpaConfig.class
})
class OrderSagaEventIdempotencyIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("logistics_order_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void registerDataSourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create");
    }

    @Autowired
    private OrderSagaEventListener orderSagaEventListener;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private ProcessedEventRepository processedEventRepository;

    @MockitoSpyBean
    private OrderSagaService orderSagaService;

    @BeforeEach
    void setUp() {
        processedEventRepository.deleteAll();
        orderRepository.deleteAll();
        clearInvocations(orderSagaService);
    }

    @Test
    @DisplayName("같은 이벤트가 순차적으로 재전달되어도 비즈니스 로직은 한 번만 실행된다")
    void listen_sameEventSequentially_processesOnlyOnce() throws Exception {
        Order order = orderRepository.save(createOrder());
        UUID deliveryId = UUID.randomUUID();
        String eventId = UUID.randomUUID().toString();
        String message = deliveryCreatedMessage(eventId, order.getId(), deliveryId);

        orderSagaEventListener.listen(message);
        orderSagaEventListener.listen(message);

        Order savedOrder = orderRepository.findById(order.getId()).orElseThrow();
        assertThat(savedOrder.getDeliveryId()).isEqualTo(deliveryId);
        assertThat(processedEventRepository.findAll())
                .singleElement()
                .satisfies(processedEvent -> {
                    assertThat(processedEvent.getEventId()).isEqualTo(eventId);
                    assertThat(processedEvent.getEventType()).isEqualTo(DELIVERY_CREATED_EVENT);
                    assertThat(processedEvent.getConsumer()).isEqualTo("order-saga-listener");
                    assertThat(processedEvent.getProcessedAt()).isNotNull();
                });
        verify(orderSagaService, times(1)).handleDeliveryCreated(order.getId(), deliveryId);
    }

    @Test
    @DisplayName("비즈니스 처리에 실패하면 이벤트 처리 이력도 롤백된다")
    void listen_businessFailure_rollsBackProcessedEvent() throws Exception {
        String eventId = UUID.randomUUID().toString();
        String message = deliveryCreatedMessage(eventId, UUID.randomUUID(), UUID.randomUUID());

        assertThatThrownBy(() -> orderSagaEventListener.listen(message))
                .isInstanceOf(ApiException.class);

        assertThat(processedEventRepository.existsById(eventId)).isFalse();
    }

    @Test
    @DisplayName("이벤트 ID가 없는 메시지는 처리하지 않는다")
    void listen_missingEventId_rejectsMessage() {
        String message = "{\"header\":{\"eventType\":\"" + DELIVERY_CREATED_EVENT + "\"},\"payload\":{}}";

        assertThatThrownBy(() -> orderSagaEventListener.listen(message))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("messageId");

        assertThat(processedEventRepository.count()).isZero();
    }

    @Test
    @DisplayName("같은 이벤트가 동시에 전달되어도 DB 고유 제약으로 한 번만 처리된다")
    void listen_sameEventConcurrently_processesOnlyOnce() throws Exception {
        Order order = orderRepository.save(createOrder());
        UUID deliveryId = UUID.randomUUID();
        String eventId = UUID.randomUUID().toString();
        String message = deliveryCreatedMessage(eventId, order.getId(), deliveryId);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<?> first = executor.submit(() -> listenAfter(start, message));
            Future<?> second = executor.submit(() -> listenAfter(start, message));

            start.countDown();
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        Order savedOrder = orderRepository.findById(order.getId()).orElseThrow();
        assertThat(savedOrder.getDeliveryId()).isEqualTo(deliveryId);
        assertThat(processedEventRepository.count()).isEqualTo(1);
        verify(orderSagaService, times(1)).handleDeliveryCreated(order.getId(), deliveryId);
    }

    private void listenAfter(CountDownLatch start, String message) {
        try {
            start.await();
            orderSagaEventListener.listen(message);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private String deliveryCreatedMessage(String eventId, UUID orderId, UUID deliveryId) throws Exception {
        EventHeader header = new EventHeader(
                eventId,
                null,
                DELIVERY_CREATED_EVENT,
                Instant.now(),
                "v1"
        );
        DeliveryCreatedPayload payload = new DeliveryCreatedPayload(orderId, deliveryId, Instant.now());
        return objectMapper.writeValueAsString(new EventEnvelope<>(header, payload));
    }

    private Order createOrder() {
        return Order.create(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                3,
                "테스트 요청사항",
                Instant.now().plusSeconds(86_400)
        );
    }

    @Configuration
    static class TestObjectMapperConfig {

        @Bean
        ObjectMapper objectMapper() {
            return JsonMapper.builder()
                    .findAndAddModules()
                    .build();
        }
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan("com.sparta.logistics.domain.entity")
    @EnableJpaRepositories("com.sparta.logistics.domain.repository")
    @EnableJpaAuditing
    static class TestJpaConfig {

        @Bean
        AuditorAware<UUID> auditorProvider() {
            return Optional::empty;
        }
    }
}
