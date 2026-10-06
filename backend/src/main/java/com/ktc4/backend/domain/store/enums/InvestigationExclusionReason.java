package com.ktc4.backend.domain.store.enums;

// 담당자가 고른 가게가 조사에서 빠진 이유. 화면이 가게별로 왜 빠졌는지 보여 줄 수 있게 이유를 나눈다.
public enum InvestigationExclusionReason {
    STORE_NOT_FOUND, // 없는 가게 번호
    DATA_PROBLEM,    // 사업자등록번호가 없거나 국세청에 없는 번호 — 번호부터 찾거나 바로잡아야 한다
    NO_NTS_CHECK,    // 국세청과 비교할 수 없음 — 아직 대조하지 않았거나 우리 상태가 UNKNOWN
    ALREADY_CLOSED   // 우리도 국세청도 폐업 — 바꿀 것도 찾아볼 것도 없다
}
