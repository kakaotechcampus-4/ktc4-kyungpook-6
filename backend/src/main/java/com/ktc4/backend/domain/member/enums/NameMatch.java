package com.ktc4.backend.domain.member.enums;

import com.ktc4.backend.domain.store.service.StoreService;

// 가입 신청서의 상호명이 후보 가게의 이름과 얼마나 같은지. 둘 다 정규화한 이름으로 비교한다.
public enum NameMatch {
    EXACT,   // 정규화한 이름이 같다
    PARTIAL, // 가게 이름 안에 신청서의 상호명이 들어 있다
    NONE;    // 겹치지 않는다 — 사업자등록번호나 휴대폰 번호로 찾은 후보

    /**
     * 후보 가게를 찾는 질의({@code StoreRepository.findOwnerCandidates})와 같은 기준으로 판정한다.
     * 신청서의 상호명이 너무 짧아 이름으로 찾지 않은 경우에는 겹쳐 보여도 {@code NONE} 이다.
     *
     * @param applicantNameNormalized 정규화한 신청서 상호명
     * @param storeNameNormalized     정규화한 가게 이름
     * @return 일치 정도
     */
    public static NameMatch of(String applicantNameNormalized, String storeNameNormalized) {
        if (applicantNameNormalized == null || storeNameNormalized == null
                || applicantNameNormalized.length() < StoreService.MIN_NAME_LENGTH_FOR_SEARCH) {
            return NONE;
        }
        if (storeNameNormalized.equals(applicantNameNormalized)) {
            return EXACT;
        }
        return storeNameNormalized.contains(applicantNameNormalized) ? PARTIAL : NONE;
    }
}
