package com.sparta.logistics.infrastructure.messaging.outbox;

import com.sparta.logistics.application.command.service.OutboxEventPublisher;
import com.sparta.logistics.common.metrics.OrderPerformanceMetrics;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
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
    private OutboxEventPublisher outboxEventPublisher;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @MockitoBean
    private RabbitTemplate rabbitTemplate;

    @MockitoBean
    private OrderPerformanceMetrics performanceMetrics;

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

    @Test
    @DisplayName("두 Publisher가 동시에 실행되어도 같은 이벤트는 한 번만 선점한다")
    void publishPendingEvents_concurrently_claimsSameEventOnlyOnce() throws Exception {
        outboxEventRepository.save(createOutboxEvent());
        CountDownLatch firstPublishStarted = new CountDownLatch(1);
        CountDownLatch releaseFirstPublish = new CountDownLatch(1);
        AtomicBoolean firstInvocation = new AtomicBoolean(true);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        doAnswer(invocation -> {
            if (firstInvocation.compareAndSet(true, false)) {
                firstPublishStarted.countDown();
                if (!releaseFirstPublish.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("첫 번째 Publisher 대기 시간이 초과되었습니다.");
                }
            }
            return null;
        }).when(rabbitTemplate).send(anyString(), anyString(), any(Message.class));

        Future<Integer> first = executor.submit(outboxEventPublisher::publishPendingEvents);

        try {
            assertThat(firstPublishStarted.await(5, TimeUnit.SECONDS)).isTrue();

            Future<Integer> second = executor.submit(outboxEventPublisher::publishPendingEvents);
            assertThat(second.get(5, TimeUnit.SECONDS)).isZero();
        } finally {
            releaseFirstPublish.countDown();
            executor.shutdown();
        }

        assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo(1);
        assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        assertThat(outboxEventRepository.findAll())
                .allSatisfy(event -> assertThat(event.getStatus()).isEqualTo(OutboxStatus.PUBLISHED));
        verify(rabbitTemplate, times(1)).send(anyString(), anyString(), any(Message.class));
    }

    @Test
    @DisplayName("두 Publisher는 잠긴 이벤트를 건너뛰고 서로 다른 배치를 병렬로 처리한다")
    void publishPendingEvents_concurrently_processesDifferentBatches() throws Exception {
        outboxEventRepository.saveAll(
                IntStream.range(0, 51)
                        .mapToObj(index -> createOutboxEvent())
                        .toList()
        );
        CountDownLatch firstPublishStarted = new CountDownLatch(1);
        CountDownLatch releaseFirstPublish = new CountDownLatch(1);
        AtomicBoolean firstInvocation = new AtomicBoolean(true);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        doAnswer(invocation -> {
            if (firstInvocation.compareAndSet(true, false)) {
                firstPublishStarted.countDown();
                if (!releaseFirstPublish.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("첫 번째 Publisher 대기 시간이 초과되었습니다.");
                }
            }
            return null;
        }).when(rabbitTemplate).send(anyString(), anyString(), any(Message.class));

        Future<Integer> first = executor.submit(outboxEventPublisher::publishPendingEvents);

        try {
            assertThat(firstPublishStarted.await(5, TimeUnit.SECONDS)).isTrue();

            Future<Integer> second = executor.submit(outboxEventPublisher::publishPendingEvents);
            assertThat(second.get(5, TimeUnit.SECONDS)).isEqualTo(1);
        } finally {
            releaseFirstPublish.countDown();
            executor.shutdown();
        }

        assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo(50);
        assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        assertThat(outboxEventRepository.findAll())
                .hasSize(51)
                .allSatisfy(event -> assertThat(event.getStatus()).isEqualTo(OutboxStatus.PUBLISHED));
        verify(rabbitTemplate, times(51)).send(anyString(), anyString(), any(Message.class));
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
