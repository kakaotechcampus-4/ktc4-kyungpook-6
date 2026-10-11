package com.ktc4.backend.domain.investigation.client;

import com.ktc4.backend.domain.investigation.dto.InvestigationTarget;
import com.ktc4.backend.domain.store.enums.StoreStatus;

/**
 * AI {@code POST /investigations} 요청 목록의 한 항목 (AI {@code InvestigationTarget}).
 * 필드 이름이 곧 AI 와의 약속이다 — 특히 {@code addressRoad} 는 AI 가 이 이름으로만 받는다.
 */
record AiInvestigationRequest(
        Long storeId,
        String name,
        String addressRoad,
        String bizNo,
        String phone,
        StoreStatus internalStatus,
        Double lat,
        Double lng
) {

    static AiInvestigationRequest from(InvestigationTarget target) {
        return new AiInvestigationRequest(
                target.storeId(), target.name(), target.addressRoad(), target.bizNo(),
                target.phone(), target.internalStatus(), target.lat(), target.lng());
    }
}
