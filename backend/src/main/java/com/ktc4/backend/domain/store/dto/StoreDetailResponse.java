package com.ktc4.backend.domain.store.dto;

import com.ktc4.backend.domain.store.enums.CompanionPolicy;
import com.ktc4.backend.domain.store.enums.OpenMode;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * 가게 한 곳의 상세 — 점주 앱의 홈·가게 관리 화면이 쓴다.
 *
 * <p>상호명·주소는 점주가 바꿀 수 없다. 관리자가 사업자등록증을 보고 승인한 값이고, 국세청 대조·AI 조사·가게 찾기의
 * 기준값이기 때문이다. 점주가 바꾸는 것은 가게 전화·영업시간·메뉴뿐이다({@link OperatingInfoRequest}).
 */
public record StoreDetailResponse(
        @Schema(description = "가게 ID", example = "12") Long storeId,
        @Schema(description = "상호명 — 점주는 바꿀 수 없다", example = "예시분식") String name,
        @Schema(description = "도로명 주소 — 점주는 바꿀 수 없다", example = "대구광역시 북구 대학로 80") String addressRoad,
        @Schema(description = "가게 전화번호 — 가입 신청의 storePhone 과 같은 값. 없으면 비어 있다", example = "053-123-4567")
        String phone,
        @Schema(description = """
                영업시간. 같은 요일이 여러 줄일 수 있고(브레이크타임), 줄이 없는 요일은 휴무다.
                점주가 연결되기 전부터 있던 가게는 빈 목록일 수 있다""")
        List<BusinessHourResponse> businessHours,
        @Schema(description = "메뉴. 등록한 순서이고, 삭제한 메뉴는 빠진다") List<MenuResponse> menus,
        @Schema(description = "영업 상태 방식 — SCHEDULE(영업시간대로) 또는 MANUAL(직접 켜고 끄기). "
                + "바꾸는 건 PUT /api/stores/{storeId}/open-setting", example = "SCHEDULE")
        OpenMode openMode,
        @Schema(description = "직접 켜고 끄기에서 켜 두었는지. 영업시간대로일 때는 쓰지 않는다", example = "true")
        boolean manualOpen,
        @Schema(description = "동반 인원 — NONE(아이 본인만)·ONE(1인 동반)·TWO(2인 동반). 점주가 정하기 전에는 NONE",
                example = "ONE")
        CompanionPolicy companion,
        @Schema(description = "포장 가능", example = "true") boolean takeoutAvailable,
        @Schema(description = "매장 식사 가능", example = "true") boolean dineInAvailable,
        @Schema(description = "사장님 한마디. 적지 않았으면 비어 있다", example = "브레이크타임 15:00 – 17:00, 하루 5명까지 제공해요")
        String ownerNote
) {
}
