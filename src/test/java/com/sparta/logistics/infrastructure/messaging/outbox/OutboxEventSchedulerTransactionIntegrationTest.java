package com.sparta.logistics.infrastructure.messaging.outbox;

import com.sparta.logistics.application.command.service.OutboxEventPublisher;
import com.sparta.logistics.domain.entity.OutboxEvent;
import com.sparta.logistics.domain.model.OutboxStatus;
import com.sparta.logistics.domain.repository.OutboxEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Bean;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@DataJpaTest
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@ContextConfiguration(classes = {
        OutboxEventPublisher.class,
        OutboxEventScheduler.class,
        OutboxEventSchedulerTransactionIntegrationTest.TestJpaConfig.class
})
class OutboxEventSchedulerTransactionIntegrationTest {

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
    private OutboxEventScheduler outboxEventScheduler;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @MockitoBean
    private RabbitTemplate rabbitTemplate;

    @BeforeEach
    void cleanUpOutboxEvents() {
        outboxEventRepository.deleteAll();
    }

    @Test
    @DisplayName("스케줄 진입점은 발행 완료 상태를 저장하고 다음 실행에서 같은 이벤트를 재발행하지 않는다")
    void publishPendingEvents_persistsPublishedStatusAndDoesNotRepublish() {
        OutboxEvent event = outboxEventRepository.save(createOutboxEvent());

        outboxEventScheduler.publishPendingEvents();
        outboxEventScheduler.publishPendingEvents();

        OutboxEvent publishedEvent = outboxEventRepository.findById(event.getId()).orElseThrow();
        assertThat(publishedEvent.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
        assertThat(publishedEvent.getPublishedAt()).isNotNull();
        assertThat(publishedEvent.getRetryCount()).isZero();

        verify(rabbitTemplate, times(1))
                .send(anyString(), anyString(), any(Message.class));
    }

    @Test
    @DisplayName("발행 실패 상태는 저장되어 다음 스케줄에서 재시도할 수 있다")
    void publishPendingEvents_failure_persistsRetryState() {
        OutboxEvent event = outboxEventRepository.save(createOutboxEvent());
        doThrow(new RuntimeException("rabbit publish failed"))
                .when(rabbitTemplate)
                .send(anyString(), anyString(), any(Message.class));

        outboxEventScheduler.publishPendingEvents();

        OutboxEvent failedEvent = outboxEventRepository.findById(event.getId()).orElseThrow();
        assertThat(failedEvent.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(failedEvent.getRetryCount()).isEqualTo(1);
        assertThat(failedEvent.getErrorMessage()).isEqualTo("rabbit publish failed");
        assertThat(failedEvent.getPublishedAt()).isNull();
    }

    private OutboxEvent createOutboxEvent() {
        return OutboxEvent.create(
                "ORDER",
                UUID.randomUUID(),
                "OrderCreatedEvent",
                "baekma.exchange",
                "order.created",
                "{\"test\":\"payload\"}"
        );
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
