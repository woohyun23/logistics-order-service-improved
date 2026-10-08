package com.sparta.logistics.common.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

@Component
public class OrderPerformanceMetrics {

    private final MeterRegistry meterRegistry;
    private final Counter outboxClaimed;
    private final DistributionSummary outboxBatchSize;

    public OrderPerformanceMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        this.outboxClaimed = Counter.builder("order.outbox.claimed")
                .description("Outbox Publisher가 선점한 이벤트 수")
                .register(meterRegistry);
        this.outboxBatchSize = DistributionSummary.builder("order.outbox.claim.batch.size")
                .description("Outbox Publisher가 한 번에 선점한 이벤트 배치 크기")
                .register(meterRegistry);
    }

    public Timer.Sample startTimer() {
        return Timer.start(meterRegistry);
    }

    public void stopTimer(Timer.Sample sample, String operation, String result) {
        sample.stop(Timer.builder("order.operation.duration")
                .description("주문 서비스 주요 작업 수행 시간")
                .tag("operation", operation)
                .tag("result", result)
                .register(meterRegistry));
    }

    public void stopConsumerTimer(Timer.Sample sample) {
        sample.stop(Timer.builder("order.consumer.duration")
                .description("Saga Consumer 전체 이벤트 처리 시간")
                .register(meterRegistry));
    }

    public void recordExternalQuery(String target, String source) {
        counter("order.external.query", "target", target, "source", source).increment();
    }

    public void recordExternalCacheLookup(String target, String result) {
        counter("order.external.cache.lookup", "target", target, "result", result).increment();
    }

    public void recordOutboxClaimed(int count) {
        outboxBatchSize.record(count);
        if (count > 0) {
            outboxClaimed.increment(count);
        }
    }

    public void recordOutboxPublish(String result) {
        counter("order.outbox.publish", "result", result).increment();
    }

    public void recordConsumerEvent(String result) {
        counter("order.consumer.event", "result", result).increment();
    }

    private Counter counter(String name, String... tags) {
        return Counter.builder(name)
                .tags(tags)
                .register(meterRegistry);
    }
}
