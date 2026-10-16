package com.ktc4.backend.domain.store.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * 가게 여러 곳을 지운 결과.
 *
 * <p>{@link StoreImportResponse} 와 같은 생각이다 — 한 건이 막혀도 나머지는 처리하고, 어느 건이 왜
 * 막혔는지까지 돌려준다. 건수만 주면 "10건 보냈는데 7건만 지워졌다" 에서 멈춘다.
 *
 * @param requested 요청에 담겨 온 건수(중복을 걸러낸 뒤)
 * @param deleted   지운 건수
 * @param notFound  이미 없던 건수 — 두 번 보내도 오류가 아니다
 * @param refused   지우지 않은 건수 — {@code refusals} 의 길이와 같다
 * @param results   지운 가게 하나하나의 결과
 * @param refusals  지우지 않은 가게와 이유
 */
public record StoreDeletionResponse(
        @Schema(description = "요청에 담겨 온 건수(중복 제거 후)", example = "10")
        int requested,

        @Schema(description = "지운 건수", example = "7")
        int deleted,

        @Schema(description = "이미 없던 건수", example = "1")
        int notFound,

        @Schema(description = "지우지 않은 건수", example = "2")
        int refused,

        @Schema(description = "지운 가게 하나하나의 결과")
        List<StoreDeleteResponse> results,

        @Schema(description = "지우지 않은 가게와 이유")
        List<Refusal> refusals
) {

    /**
     * 지우지 않은 한 건.
     *
     * @param storeId 그 가게 ID
     * @param name    그 가게 이름
     * @param reason  지우지 않은 이유 — 사용자에게 보이는 문구는 {@code ErrorCode} 의 것을 그대로 쓴다
     */
    public record Refusal(
            @Schema(description = "가게 ID", example = "7")
            Long storeId,

            @Schema(description = "가게 이름", example = "한빛국밥")
            String name,

            @Schema(description = "지우지 않은 이유", example = "점주가 연결된 가게입니다")
            String reason
    ) {
    }

    /**
     * 결과를 만든다. {@code deleted}·{@code refused} 는 따로 받지 않고 목록에서 센다 —
     * 두 값이 어긋날 수 있는 길을 두지 않는다({@link StoreImportResponse#of} 와 같다).
     *
     * @param requested 요청 건수(중복 제거 후)
     * @param notFound  이미 없던 건수
     * @param results   지운 가게의 결과
     * @param refusals  지우지 않은 가게와 이유
     * @return 삭제 결과
     */
    public static StoreDeletionResponse of(int requested, int notFound,
                                           List<StoreDeleteResponse> results, List<Refusal> refusals) {
        return new StoreDeletionResponse(requested, results.size(), notFound, refusals.size(),
                List.copyOf(results), List.copyOf(refusals));
    }
}
