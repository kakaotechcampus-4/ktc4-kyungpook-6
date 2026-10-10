package com.ktc4.backend.domain.investigation.client;

import java.util.List;
import java.util.Map;

/**
 * AI {@code POST /investigations} 응답 (AI {@code InvestigationResponse}).
 *
 * <p>enum 값도 문자열로 받는다 — 모르는 값을 Jackson 이 아니라 {@link AiApiClient} 가 직접 걸러
 * {@link AiContractError} 로 바꾸기 위해서다. 우리가 쓰지 않는 필드({@code mapCheck}, {@code evidences},
 * {@code sourceCount})는 받지 않는다.
 */
record AiInvestigationResponse(
        List<Result> results,
        Integer requested,
        Integer succeeded
) {

    /** AI {@code StoreFinding}. */
    record Result(
            Long storeId,
            String classification,
            Map<String, String> proposedChanges,
            List<SignalItem> signals,
            String failure
    ) {
    }

    /** AI {@code Signal}. */
    record SignalItem(
            String signalType,
            String field,
            String observed,
            String evidenceText,
            String evidenceUrl
    ) {
    }
}
