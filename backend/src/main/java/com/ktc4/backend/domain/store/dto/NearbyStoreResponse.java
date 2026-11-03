package com.ktc4.backend.domain.store.dto;

import com.ktc4.backend.domain.store.enums.CompanionPolicy;
import com.ktc4.backend.domain.store.enums.OpenMode;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * 아동 앱의 주변 가게 한 곳 — 지도·목록 칩·가게 정보를 이것 하나로 그린다(상세 API 를 따로 두지 않는다).
 *
 * <p>영업중·쉬는중 같은 상태는 담지 않는다. 앱이 {@code openMode}·{@code manualOpen}·영업시간으로 기기 시각 기준으로 계산한다.
 */
public record NearbyStoreResponse(
        @Schema(description = "가게 ID", example = "12") Long storeId,
        @Schema(description = "상호명", example = "예시분식") String name,
        @Schema(description = "도로명 주소", example = "가상특별시 예시구 샘플로 123") String addressRoad,
        @Schema(description = "위도", example = "35.8884") double lat,
        @Schema(description = "경도", example = "128.6107") double lng,
        @Schema(description = "요청한 위치에서 가게까지 직선 거리(m, 반올림)", example = "350") int distanceMeters,
        @Schema(description = "가게 전화번호. 없으면 비어 있다", example = "053-000-0000") String phone,
        @Schema(description = "영업 상태 방식 — SCHEDULE(영업시간대로) 또는 MANUAL(직접 켜고 끄기)", example = "SCHEDULE")
        OpenMode openMode,
        @Schema(description = "직접 켜고 끄기에서 켜 두었는지. MANUAL 인데 false 면 쉬는중", example = "true")
        boolean manualOpen,
        @Schema(description = "영업시간. 요일 순(월 → 일), 같은 요일은 여는 시간 순. 줄이 없는 요일은 휴무")
        List<BusinessHourResponse> businessHours,
        @Schema(description = "메뉴. 등록한 순서이고, 삭제한 메뉴는 빠진다") List<NearbyStoreMenuResponse> menus,
        @Schema(description = "동반 인원 — NONE(아이 본인만)·ONE(1인 동반)·TWO(2인 동반). 점주가 정하기 전에는 NONE",
                example = "ONE")
        CompanionPolicy companion,
        @Schema(description = "포장 가능", example = "true") boolean takeoutAvailable,
        @Schema(description = "매장 식사 가능", example = "true") boolean dineInAvailable,
        @Schema(description = "사장님 한마디. 점주가 적지 않았으면 비어 있다", example = "브레이크타임 15:00 – 17:00, 하루 5명까지 제공해요")
        String ownerNote
) {
}
