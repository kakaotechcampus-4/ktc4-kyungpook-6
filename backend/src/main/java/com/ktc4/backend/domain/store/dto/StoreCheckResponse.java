package com.ktc4.backend.domain.store.dto;

import com.ktc4.backend.domain.business.dto.BusinessStatus;
import com.ktc4.backend.domain.business.enums.BusinessState;
import com.ktc4.backend.domain.store.entity.Store;
import com.ktc4.backend.domain.store.enums.NtsLookupResult;
import com.ktc4.backend.domain.store.enums.StatusComparison;
import com.ktc4.backend.domain.store.enums.StoreStatus;

import java.time.LocalDate;

/**
 * AI 1차 조사에 넘기는 가게별 자료.
 *
 * <p>AI 가 이름·주소로 가게를 찾아볼 수 있도록 원본과 정규화 값을 함께 담는다.
 * 우리 상태와 국세청 상태가 다른지는 코드가 {@code statusComparison}(어떻게) / {@code statusMismatch}(다른가) 로 계산해 두고,
 * 그 불일치가 실제 폐업을 뜻하는지 같은 해석만 AI 에 맡긴다.
 * {@code ntsStatus} / {@code ntsClosedAt} 이 비어 있는 이유는 {@code ntsLookup} 으로 구분한다.
 *
 * <p>대응이 다른 두 가지를 따로 담는다 — {@code statusMismatch} 는 가게가 정말 폐업했는지
 * AI 가 조사할 대상이고, {@code dataProblem} 은 사업자등록번호가 없거나 틀려서 사람이 데이터를
 * 고쳐야 하는 대상이다. 번호가 틀린 가게를 조사에 넘기면 엉뚱한 가게를 보게 된다.
 */
public record StoreCheckResponse(
        Long storeId,
        String name,
        String nameNormalized,
        String addressRoad,
        String addressNormalized,
        String phone,
        String bizNo,
        StoreStatus internalStatus,
        NtsLookupResult ntsLookup,
        BusinessState ntsStatus,
        LocalDate ntsClosedAt,
        StatusComparison statusComparison,
        boolean statusMismatch,
        boolean dataProblem
) {

    public static StoreCheckResponse of(Store store, NtsLookupResult ntsLookup, BusinessStatus nts) {
        BusinessState ntsStatus = nts == null ? null : nts.state();
        // 두 값을 여기서 함께 계산해야 "다른가" 와 "어떻게" 가 서로 어긋나지 않는다
        StatusComparison comparison = StatusComparison.of(store.getStatus(), ntsStatus);
        return new StoreCheckResponse(
                store.getStoreId(),
                store.getName(),
                store.getNameNormalized(),
                store.getAddressRoad(),
                store.getAddressNormalized(),
                store.getPhone(),
                store.getBizNo(),
                store.getStatus(),
                ntsLookup,
                ntsStatus,
                nts == null ? null : nts.closedAt(),
                comparison,
                comparison.isMismatch(),
                ntsLookup.isDataProblem() || comparison.isDataProblem()
        );
    }
}
