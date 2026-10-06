package com.sparta.logistics.application.query.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

@Schema(description = "외부 서비스 조회 결과")
public record ExternalLookupResponse<T>(
        @Schema(description = "외부 서비스 응답 데이터")
        T data,

        @Schema(description = "데이터 출처", example = "LIVE")
        ExternalDataSource source,

        @Schema(description = "외부 서비스에서 실제로 조회한 시각")
        Instant fetchedAt
) {
    public static <T> ExternalLookupResponse<T> live(T data, Instant fetchedAt) {
        return new ExternalLookupResponse<>(data, ExternalDataSource.LIVE, fetchedAt);
    }

    public static <T> ExternalLookupResponse<T> cached(T data, Instant fetchedAt) {
        return new ExternalLookupResponse<>(data, ExternalDataSource.CACHE, fetchedAt);
    }
}
