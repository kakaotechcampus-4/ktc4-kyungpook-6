package com.ktc4.backend.domain.store.enums;

// 가게 한 곳의 국세청 조회가 어떻게 끝났는지. 국세청 칸이 비어 있는 이유를 구분하기 위해 둔다.
public enum NtsLookupResult {
    CONFIRMED,   // 국세청 상태를 확인함
    UNCONFIRMED, // 조회를 요청했지만 응답이 없거나 해석하지 못함 — 다시 조회하면 확인될 수 있음
    NO_BIZ_NO;   // 사업자번호가 없거나 형식이 틀려 조회하지 않음 — 데이터를 고쳐야 함

    /**
     * 우리 DB 의 사업자등록번호가 잘못됐다고 볼 수 있는가 — 사람이 데이터를 고쳐야 하는 건이다.
     *
     * <p>{@link #UNCONFIRMED} 는 다시 조회하면 확인될 수 있어 데이터 문제로 보지 않는다.
     * 번호가 있는데 국세청에 없는 경우는 {@code StatusComparison.NTS_NOT_REGISTERED} 가 맡는다.
     */
    public boolean isDataProblem() {
        return this == NO_BIZ_NO;
    }
}
