package com.ktc4.backend.domain.store.enums;

import com.ktc4.backend.domain.business.enums.BusinessState;

import java.util.Optional;

// 우리 DB 상태와 국세청 상태를 값으로만 비교한 결과. 답이 하나로 정해지는 비교라 코드가 계산하고,
// 불일치면 국세청 상태로 바꾸자는 수정안까지 코드가 만든다(proposedStatus). 받아들일지는 담당자가 정한다.
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
     * 두 상태가 서로 다른가 — 1차 조사(국세청 대조)만으로 수정안을 낼 대상을 고를 때 쓴다.
     * 이 가게들은 국세청이 답을 이미 줬으므로 AI 조사로 넘기지 않는다.
     *
     * <p>{@link #NTS_NOT_REGISTERED} 는 제외한다. 그건 상태가 다른 게 아니라 우리 DB 의 번호가
     * 틀렸다는 뜻이라, 조사가 아니라 번호를 바로잡는 일이 필요하다.
     * 번호가 틀린 가게에 수정안을 내면 엉뚱한 사업자의 상태를 제안하게 된다.
     */
    public boolean isMismatch() {
        return this != MATCH && this != NOT_COMPARABLE && this != NTS_NOT_REGISTERED;
    }

    /**
     * 국세청 상태에 맞추려면 우리 DB 상태를 무엇으로 바꿔야 하는가 — 1차 조사(국세청 대조)의 수정안이다.
     *
     * <p>국세청이 답한 상태를 그대로 제안한다. 1차는 아는 것까지만 제안하고, 그 불일치가 실제로 무엇을
     * 뜻하는지(예: 폐업이 아니라 이전 개업)는 수정안을 받아 본 담당자가 판단한다.
     *
     * <p>제안할 것이 없으면 비어 있다 — 두 상태가 같거나, 비교할 수 없거나, 번호부터 바로잡아야 하는 경우다.
     * {@link #isMismatch()} 가 참인 값만 제안이 있다.
     *
     * <p>{@code default} 를 두지 않는다. 값이 추가되면 컴파일이 실패해 이 매핑을 빠뜨릴 수 없다.
     */
    public Optional<StoreStatus> proposedStatus() {
        return switch (this) {
            case OPEN_BUT_SUSPENDED, CLOSED_BUT_SUSPENDED -> Optional.of(StoreStatus.SUSPENDED);
            case OPEN_BUT_CLOSED, SUSPENDED_BUT_CLOSED -> Optional.of(StoreStatus.CLOSED);
            case SUSPENDED_BUT_ACTIVE, CLOSED_BUT_ACTIVE -> Optional.of(StoreStatus.OPEN);
            case MATCH, NTS_NOT_REGISTERED, NOT_COMPARABLE -> Optional.empty();
        };
    }
}
