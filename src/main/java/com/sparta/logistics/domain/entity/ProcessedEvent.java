package com.sparta.logistics.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Table(name = "p_processed_events")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProcessedEvent {

    @Id
    @Column(name = "event_id", nullable = false, updatable = false, length = 36)
    private String eventId;

    @Column(name = "event_type", nullable = false, updatable = false, length = 100)
    private String eventType;

    @Column(name = "consumer", nullable = false, updatable = false, length = 100)
    private String consumer;

    @Column(name = "processed_at", nullable = false, updatable = false)
    private Instant processedAt;
}
