package com.ktc4.backend.domain.store.enums;

// 가게 한 곳의 국세청 조회가 어떻게 끝났는지. 국세청 칸이 비어 있는 이유를 구분하기 위해 둔다.
public enum NtsLookupResult {
    CONFIRMED,   // 국세청 상태를 확인함
    UNCONFIRMED, // 조회를 요청했지만 응답이 없거나 해석하지 못함 — 다시 조회하면 확인될 수 있음
    NO_BIZ_NO    // 사업자번호가 없거나 형식이 틀려 조회하지 않음 — 데이터를 고쳐야 함
}
