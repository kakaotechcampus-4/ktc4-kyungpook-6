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
        LocalDateTime lastCheckedAt,

        @Schema(description = """
                마지막으로 값이 바뀐 시각. 한 번도 바뀌지 않았으면 비어 있습니다.
                위의 lastCheckedAt(사람이 직접 본 날)과 다릅니다 — 수정안을 반영해도 확인일은 오르지 않습니다.""",
                example = "2026-09-02T14:30:00")
        LocalDateTime lastChangedAt
) {

    /**
     * 변경 시각을 아직 모를 때 쓴다. {@code lastChangedAt} 은 비어 나간다.
     *
     * <p>화면에 날짜 둘을 보여주는 목록 조회에서는 {@link #of(Store, LocalDateTime)} 로 채워 보낸다.
     */
    public static StoreResponse from(Store store) {
        return of(store, null);
    }

    /**
     * @param store         가게
     * @param lastChangedAt 그 가게가 마지막으로 바뀐 시각. 바뀐 적이 없으면 {@code null}
     */
    public static StoreResponse of(Store store, LocalDateTime lastChangedAt) {
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
                store.getLastCheckedAt(),
                lastChangedAt
        );
    }
}
