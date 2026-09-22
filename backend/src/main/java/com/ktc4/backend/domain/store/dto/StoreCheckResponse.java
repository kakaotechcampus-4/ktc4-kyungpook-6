package com.ktc4.backend.domain.store.dto;

import com.ktc4.backend.domain.business.enums.BusinessState;
import com.ktc4.backend.domain.store.entity.Store;
import com.ktc4.backend.domain.store.enums.NtsLookupResult;
import com.ktc4.backend.domain.store.enums.StatusComparison;
import com.ktc4.backend.domain.store.enums.StoreStatus;
import com.ktc4.backend.domain.store.ntscheck.entity.StoreNtsCheck;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * AI 1차 조사에 넘기는 가게별 자료.
 *
 * <p>AI 가 이름·주소·좌표로 가게를 찾아볼 수 있도록 원본과 정규화 값을 함께 담는다.
 * 우리 상태와 국세청 상태가 다른지는 코드가 {@code statusComparison}(어떻게) / {@code statusMismatch}(다른가)
 * 로 계산해 두고, 그 불일치가 실제 폐업을 뜻하는지 같은 해석만 AI 에 맡긴다.
 *
 * <p>대응이 다른 두 가지를 따로 담는다 — {@code statusMismatch} 는 가게가 정말 폐업했는지
 * AI 가 조사할 대상이고, {@code dataProblem} 은 사업자등록번호가 없거나 틀려서 사람이 데이터를
 * 고쳐야 하는 대상이다. 번호가 틀린 가게를 조사에 넘기면 엉뚱한 가게를 보게 된다.
 *
 * <p>{@code ntsStatus} / {@code ntsClosedAt} 이 비어 있는 이유는 {@code ntsLookup} 으로 구분하고,
 * 그 값이 언제 기준인지는 {@code ntsCheckedAt}(마지막으로 국세청 확인에 성공한 시각)으로 알 수 있다.
 */
public record StoreCheckResponse(
        Long storeId,
        String name,
        String nameNormalized,
        String addressRoad,
        String addressNormalized,
        Double lat,
        Double lng,
        String phone,
        String bizNo,
        StoreStatus internalStatus,
        NtsLookupResult ntsLookup,
        BusinessState ntsStatus,
        LocalDate ntsClosedAt,
        StatusComparison statusComparison,
        boolean statusMismatch,
        boolean dataProblem,
        LocalDateTime ntsCheckedAt
) {

    /**
     * 가게와 국세청 확인 기록을 합쳐 조사 자료 한 건을 만든다.
     *
     * <p>배치가 아직 확인하지 않아 기록이 없으면 "조회했지만 확인하지 못한 것"과 대응이 같아
     * {@link NtsLookupResult#UNCONFIRMED} 로 본다 — 다음 배치에서 확인될 수 있기 때문이다.
     *
     * @param row 가게와 국세청 확인 기록(없을 수 있음)
     * @return 조사 자료 한 건
     */
    public static StoreCheckResponse from(StoreWithNtsCheck row) {
        Store store = row.store();
        StoreNtsCheck check = row.check();

        NtsLookupResult ntsLookup = check == null ? NtsLookupResult.UNCONFIRMED : check.getCheckResult();
        BusinessState ntsStatus = check == null ? null : check.getNtsState();
        // 두 값을 여기서 함께 계산해야 "다른가" 와 "어떻게" 가 서로 어긋나지 않는다
        StatusComparison comparison = StatusComparison.of(store.getStatus(), ntsStatus);

        return new StoreCheckResponse(
                store.getStoreId(),
                store.getName(),
                store.getNameNormalized(),
                store.getAddressRoad(),
                store.getAddressNormalized(),
                store.getLat(),
                store.getLng(),
                store.getPhone(),
                store.getBizNo(),
                store.getStatus(),
                ntsLookup,
                ntsStatus,
                check == null ? null : check.getNtsClosedAt(),
                comparison,
                comparison.isMismatch(),
                ntsLookup.isDataProblem() || comparison.isDataProblem(),
                check == null ? null : check.getLastSuccessAt()
        );
    }
}
