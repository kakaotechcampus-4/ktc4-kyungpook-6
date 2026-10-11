package com.ktc4.backend.domain.store.dto;

import com.ktc4.backend.domain.store.enums.InvestigationExclusionReason;

/**
 * 조사에서 빠진 가게 한 곳과 그 이유.
 *
 * <p>없는 가게 번호도 담아야 해서 가게 정보가 아니라 요청받은 번호만 둔다.
 */
public record ExcludedStore(
        Long storeId,
        InvestigationExclusionReason reason
) {
}
