package com.ktc4.backend.domain.investigation.dto;

import com.ktc4.backend.domain.business.enums.BusinessState;
import com.ktc4.backend.domain.store.dto.StoreCheckResponse;
import com.ktc4.backend.domain.store.enums.StatusComparison;

/**
 * 국세청과 상태가 달라 AI 없이 1차 수정안으로 끝나는 가게. 수정안은 {@code statusComparison} 만으로 정해진다.
 *
 * @param storeId          가게 ID
 * @param statusComparison 우리 상태와 국세청 상태를 비교한 결과 — 항상 불일치({@code isMismatch()})
 * @param ntsStatus        국세청이 답한 사업자 상태 — 근거 문구에 쓴다
 */
public record NtsTarget(Long storeId, StatusComparison statusComparison, BusinessState ntsStatus) {

    /** 조사 대상 나누기({@code InvestigationTargetSelector})가 돌려준 가게 자료에서 만든다. */
    public static NtsTarget from(StoreCheckResponse check) {
        return new NtsTarget(check.storeId(), check.statusComparison(), check.ntsStatus());
    }
}
