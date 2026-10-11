package com.ktc4.backend.domain.store.dto;

import java.util.List;

/**
 * 담당자가 고른 가게를 1차 조사(국세청 대조) 결과로 나눈 것. 고른 가게는 셋 중 정확히 한 곳에 들어간다.
 *
 * @param aiTargets     국세청과 상태가 같은 가게 — 국세청이 알려 주지 못하는 변화를 AI 가 조사한다
 * @param resolvedByNts 국세청과 상태가 다른 가게 — 1차 수정안으로 끝나고 AI 는 부르지 않는다
 * @param excluded      조사하지 않는 가게와 그 이유
 */
public record InvestigationTargets(
        List<StoreCheckResponse> aiTargets,
        List<StoreCheckResponse> resolvedByNts,
        List<ExcludedStore> excluded
) {
}
