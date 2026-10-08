package com.sparta.logistics.performance;

import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

public final class RabbitMessageGenerator {

    private RabbitMessageGenerator() {
    }

    public static void main(String[] args) throws Exception {
        Configuration configuration = Configuration.fromEnvironment();
        String template = loadTemplate(configuration.templateFile());

        CachingConnectionFactory connectionFactory = new CachingConnectionFactory(
                configuration.host(),
                configuration.port()
        );
        connectionFactory.setUsername(configuration.username());
        connectionFactory.setPassword(configuration.password());

        RabbitTemplate rabbitTemplate = new RabbitTemplate(connectionFactory);
        ExecutorService executor = Executors.newFixedThreadPool(configuration.concurrency());
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger sequence = new AtomicInteger();
        LongAdder sent = new LongAdder();
        LongAdder failed = new LongAdder();
        String fixedMessageId = configuration.fixedMessageId() == null
                ? UUID.randomUUID().toString()
                : configuration.fixedMessageId();
        List<Future<?>> futures = new ArrayList<>();
        Instant startedAt = Instant.now();

        try {
            for (int worker = 0; worker < configuration.concurrency(); worker++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    int current;
                    while ((current = sequence.getAndIncrement()) < configuration.messageCount()) {
                        String messageId = configuration.messageIdMode() == MessageIdMode.FIXED
                                ? fixedMessageId
                                : UUID.randomUUID().toString();
                        String payload = render(
                                template,
                                messageId,
                                current,
                                Instant.now(),
                                configuration.orderId(),
                                configuration.deliveryId()
                        );
                        Message message = MessageBuilder
                                .withBody(payload.getBytes(StandardCharsets.UTF_8))
                                .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                                .setMessageId(messageId)
                                .build();
                        try {
                            rabbitTemplate.send(
                                    configuration.exchange(),
                                    configuration.routingKey(),
                                    message
                            );
                            sent.increment();
                        } catch (RuntimeException e) {
                            failed.increment();
                        }
                    }
                    return null;
                }));
            }

            start.countDown();
            for (Future<?> future : futures) {
                future.get();
            }
        } finally {
            executor.shutdownNow();
            connectionFactory.destroy();
        }

        long elapsedMillis = Duration.between(startedAt, Instant.now()).toMillis();
        double messagesPerSecond = elapsedMillis == 0
                ? sent.sum()
                : sent.sum() * 1000.0 / elapsedMillis;

        System.out.printf(
                Locale.ROOT,
                "requested=%d sent=%d failed=%d elapsedMs=%d messagesPerSecond=%.2f messageIdMode=%s%n",
                configuration.messageCount(),
                sent.sum(),
                failed.sum(),
                elapsedMillis,
                messagesPerSecond,
                configuration.messageIdMode()
        );

        if (failed.sum() > 0) {
            throw new IllegalStateException("일부 RabbitMQ 메시지 발행에 실패했습니다. failed=" + failed.sum());
        }
    }

    private static String loadTemplate(String templateFile) throws IOException {
        if (templateFile != null && !templateFile.isBlank()) {
            return Files.readString(Path.of(templateFile), StandardCharsets.UTF_8);
        }

        try (InputStream input = RabbitMessageGenerator.class.getResourceAsStream("/default-message.json")) {
            if (input == null) {
                throw new IllegalStateException("기본 메시지 템플릿을 찾을 수 없습니다.");
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String render(
            String template,
            String messageId,
            int sequence,
            Instant timestamp,
            String orderId,
            String deliveryId
    ) {
        return template
                .replace("{{messageId}}", messageId)
                .replace("{{sequence}}", Integer.toString(sequence))
                .replace("{{timestamp}}", timestamp.toString())
                .replace("{{orderId}}", orderId)
                .replace("{{deliveryId}}", deliveryId);
    }

    private enum MessageIdMode {
        UNIQUE,
        FIXED
    }

    private record Configuration(
            String host,
            int port,
            String username,
            String password,
            String exchange,
            String routingKey,
            int messageCount,
            int concurrency,
            MessageIdMode messageIdMode,
            String fixedMessageId,
            String templateFile,
            String orderId,
            String deliveryId
    ) {
        private static Configuration fromEnvironment() {
            int messageCount = positiveInt("MESSAGE_COUNT", 1);
            int concurrency = Math.min(positiveInt("MESSAGE_CONCURRENCY", 1), messageCount);
            MessageIdMode messageIdMode = MessageIdMode.valueOf(
                    environment("MESSAGE_ID_MODE", "unique").toUpperCase(Locale.ROOT)
            );

            return new Configuration(
                    environment("RABBITMQ_HOST", "localhost"),
                    positiveInt("RABBITMQ_PORT", 15672),
                    environment("RABBITMQ_USERNAME", "order"),
                    environment("RABBITMQ_PASSWORD", "order"),
                    environment("MESSAGE_EXCHANGE", "baekma.exchange"),
                    environment("MESSAGE_ROUTING_KEY", "order.created"),
                    messageCount,
                    concurrency,
                    messageIdMode,
                    System.getenv("FIXED_MESSAGE_ID"),
                    System.getenv("MESSAGE_TEMPLATE_FILE"),
                    environment("ORDER_ID", UUID.randomUUID().toString()),
                    environment("DELIVERY_ID", UUID.randomUUID().toString())
            );
        }

        private static int positiveInt(String name, int defaultValue) {
            int value = Integer.parseInt(environment(name, Integer.toString(defaultValue)));
            if (value < 1) {
                throw new IllegalArgumentException(name + " 값은 1 이상이어야 합니다.");
            }
            return value;
        }

        private static String environment(String name, String defaultValue) {
            return System.getenv().getOrDefault(name, defaultValue);
        }
    }
}
