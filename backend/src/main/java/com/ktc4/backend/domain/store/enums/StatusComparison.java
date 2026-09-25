package com.ktc4.backend.domain.store.enums;

import com.ktc4.backend.domain.business.enums.BusinessState;

// 우리 DB 상태와 국세청 상태를 값으로만 비교한 결과. 답이 하나로 정해지는 비교라 코드가 계산하고,
// 그 불일치가 실제 폐업을 뜻하는지 같은 해석은 AI 조사에 맡긴다.
public enum StatusComparison {
    MATCH,                // 두 상태가 같음
    OPEN_BUT_SUSPENDED,   // 우리: 영업중 / 국세청: 휴업
    OPEN_BUT_CLOSED,      // 우리: 영업중 / 국세청: 폐업
    SUSPENDED_BUT_ACTIVE, // 우리: 휴업 / 국세청: 계속
    SUSPENDED_BUT_CLOSED, // 우리: 휴업 / 국세청: 폐업
    CLOSED_BUT_ACTIVE,    // 우리: 폐업 / 국세청: 계속
    CLOSED_BUT_SUSPENDED, // 우리: 폐업 / 국세청: 휴업
    NTS_NOT_REGISTERED,   // 국세청에 없는 번호 — 번호가 잘못 적혔을 수 있음
    NOT_COMPARABLE;       // 우리 상태가 UNKNOWN 이거나 국세청 상태를 확인하지 못함 (번호가 지워진 가게도 응답에서 이 값)

    public static StatusComparison of(StoreStatus internal, BusinessState nts) {
        if (internal == StoreStatus.UNKNOWN || nts == null) {
            return NOT_COMPARABLE;
        }
        return switch (nts) {
            case NOT_REGISTERED -> NTS_NOT_REGISTERED;
            case ACTIVE -> switch (internal) {
                case OPEN -> MATCH;
                case SUSPENDED -> SUSPENDED_BUT_ACTIVE;
                case CLOSED -> CLOSED_BUT_ACTIVE;
                case UNKNOWN -> NOT_COMPARABLE;
            };
            case SUSPENDED -> switch (internal) {
                case OPEN -> OPEN_BUT_SUSPENDED;
                case SUSPENDED -> MATCH;
                case CLOSED -> CLOSED_BUT_SUSPENDED;
                case UNKNOWN -> NOT_COMPARABLE;
            };
            case CLOSED -> switch (internal) {
                case OPEN -> OPEN_BUT_CLOSED;
                case SUSPENDED -> SUSPENDED_BUT_CLOSED;
                case CLOSED -> MATCH;
                case UNKNOWN -> NOT_COMPARABLE;
            };
        };
    }

    /**
     * 두 상태가 서로 다른가 — AI 조사(가게가 정말 폐업했는지 확인)로 넘길 대상을 고를 때 쓴다.
     *
     * <p>{@link #NTS_NOT_REGISTERED} 는 제외한다. 그건 상태가 다른 게 아니라 우리 DB 의 번호가
     * 틀렸다는 뜻이라, 조사가 아니라 번호를 바로잡는 일이 필요하다.
     * 번호가 틀린 가게를 AI 에 넘기면 엉뚱한 가게를 조사하게 된다.
     */
    public boolean isMismatch() {
        return this != MATCH && this != NOT_COMPARABLE && this != NTS_NOT_REGISTERED;
    }
}
