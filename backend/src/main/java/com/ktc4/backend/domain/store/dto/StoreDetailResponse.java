package com.ktc4.backend.domain.store.dto;

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
        @Schema(description = "메뉴. 등록한 순서이고, 삭제한 메뉴는 빠진다") List<MenuResponse> menus
) {
}
