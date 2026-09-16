package com.ktc4.backend.domain.store.dto;

import com.ktc4.backend.domain.business.enums.BusinessState;
import com.ktc4.backend.domain.store.entity.Store;
import com.ktc4.backend.domain.store.enums.StoreStatus;

import java.time.LocalDate;

/**
 * AI 1차 조사에 넘기는 가게별 자료.
 *
 * <p>우리 DB 상태({@code internalStatus})와 국세청 상태({@code ntsStatus})를 판정 없이 나란히 담는다.
 * AI 가 이름·주소로 가게를 찾아볼 수 있도록 원본과 정규화 값을 함께 넣는다.
 * {@code ntsStatus} / {@code ntsClosedAt} 은 사업자번호가 없거나 형식이 틀렸거나 조회에 실패하면 null 이다.
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
        BusinessState ntsStatus,
        LocalDate ntsClosedAt
) {

    public static StoreCheckResponse of(Store store, BusinessState ntsStatus, LocalDate ntsClosedAt) {
        return new StoreCheckResponse(
                store.getStoreId(),
                store.getName(),
                store.getNameNormalized(),
                store.getAddressRoad(),
                store.getAddressNormalized(),
                store.getPhone(),
                store.getBizNo(),
                store.getStatus(),
                ntsStatus,
                ntsClosedAt
        );
    }
}
