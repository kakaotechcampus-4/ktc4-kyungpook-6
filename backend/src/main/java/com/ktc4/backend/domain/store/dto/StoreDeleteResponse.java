package com.ktc4.backend.domain.store.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 가게 한 곳을 지운 결과.
 *
 * <p>딸린 기록을 몇 줄 지웠는지까지 담는다. "가게를 지웠다" 만 돌려주면, 조사 결과나 국세청 기록이
 * 함께 사라진 것을 부른 쪽이 모른 채 지나간다 — 되돌릴 수 없는 일이므로 무엇이 사라졌는지 남긴다.
 *
 * @param storeId       지운 가게 ID
 * @param name          지운 가게 이름 — 번호만 남으면 무엇을 지웠는지 기록에서 알 수 없다
 * @param ntsChecks     함께 지운 국세청 확인 기록 수(0 또는 1)
 * @param ntsChanges    함께 지운 국세청 상태 변경 이력 수
 * @param menus         함께 지운 메뉴 수
 * @param businessHours 함께 지운 영업시간 수
 * @param tasks         함께 지운 조사 결과 수
 * @param verifications 함께 지운 확인 기록 수
 * @param signals       함께 지운 신호 수
 * @param changeHistories 함께 지운 가게 변경 기록 수
 * @param ownerLinks    함께 끊은 점주 연결 수 — {@code force} 로 지웠을 때만 0보다 크다
 */
public record StoreDeleteResponse(
        @Schema(description = "지운 가게 ID", example = "12")
        Long storeId,

        @Schema(description = "지운 가게 이름", example = "가나다분식")
        String name,

        @Schema(description = "함께 지운 국세청 확인 기록 수", example = "1")
        int ntsChecks,

        @Schema(description = "함께 지운 국세청 상태 변경 이력 수", example = "2")
        int ntsChanges,

        @Schema(description = "함께 지운 메뉴 수", example = "0")
        int menus,

        @Schema(description = "함께 지운 영업시간 수", example = "0")
        int businessHours,

        @Schema(description = "함께 지운 조사 결과 수", example = "3")
        int tasks,

        @Schema(description = "함께 지운 확인 기록 수", example = "1")
        int verifications,

        @Schema(description = "함께 지운 신호 수", example = "4")
        int signals,

        @Schema(description = "함께 지운 가게 변경 기록 수", example = "2")
        int changeHistories,

        @Schema(description = "함께 끊은 점주 연결 수. 0보다 크면 그 점주의 앱에서 이 가게가 사라집니다",
                example = "0")
        int ownerLinks
) {
}
