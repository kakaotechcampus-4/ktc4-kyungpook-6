package com.ktc4.backend.domain.store.dto;

import com.ktc4.backend.domain.store.entity.Store;
import com.ktc4.backend.domain.store.enums.StoreStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

/**
 * 가게 정보 응답 DTO.
 *
 * <p>{@code nameNormalized} / {@code addressNormalized} 는 중복 판별·외부 데이터 매칭에만 쓰는
 * 내부 값이라 의도적으로 응답에서 제외했다.
 */
public record StoreResponse(
        @Schema(description = "가게 ID", example = "1")
        Long storeId,

        @Schema(description = "가게명", example = "예시가게")
        String name,

        @Schema(description = "도로명 주소", example = "가상특별시 예시구 샘플로 123")
        String addressRoad,

        @Schema(description = "위도", example = "12.3456")
        Double lat,

        @Schema(description = "경도", example = "123.4567")
        Double lng,

        @Schema(description = "영업 상태 (OPEN: 영업중, SUSPENDED: 휴업, CLOSED: 폐업, UNKNOWN: 미확인)", example = "OPEN")
        StoreStatus status,

        @Schema(description = "업종", example = "분식")
        String category,

        @Schema(description = "전화번호", example = "000-1234-5678")
        String phone,

        @Schema(description = "사업자등록번호", example = "123-45-67890")
        String bizNo,

        @Schema(description = "마지막으로 정보가 확인된 시각", example = "2026-09-01T10:00:00")
        LocalDateTime lastCheckedAt
) {

    public static StoreResponse from(Store store) {
        return new StoreResponse(
                store.getStoreId(),
                store.getName(),
                store.getAddressRoad(),
                store.getLat(),
                store.getLng(),
                store.getStatus(),
                store.getCategory(),
                store.getPhone(),
                store.getBizNo(),
                store.getLastCheckedAt()
        );
    }
}
