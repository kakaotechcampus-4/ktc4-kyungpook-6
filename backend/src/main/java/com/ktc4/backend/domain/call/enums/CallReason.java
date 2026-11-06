package com.ktc4.backend.domain.call.enums;

import com.ktc4.backend.domain.task.enums.TaskClassification;

import java.util.Optional;

/**
 * 이 가게에 왜 전화를 걸었는가. 조사 판정({@link TaskClassification})에서 정해진다.
 *
 * <p>판정을 그대로 저장하지 않고 따로 두는 이유는 두 가지다. 판정은 조사 결과의 성질이고 이것은
 * 전화를 건 이유라 수명이 다르다(판정이 뒤에 바뀌어도 그때 왜 걸었는지는 그대로여야 한다). 그리고
 * 조사 결과 없이 가게 목록에서 바로 거는 길이 생기면 그때는 판정이 아예 없다.
 *
 * <p>DB 에는 이름 문자열로 저장한다({@code @Enumerated(EnumType.STRING)}).
 */
public enum CallReason {
    /** 변화가 잡혀서 — 조사가 찾아낸 값이 맞는지 점주에게 직접 확인한다. */
    CHANGE_DETECTED,
    /** 담당자가 직접 확인한 지 90일 이상 지나서 — 바뀐 것이 없는지 훑어 묻는다. */
    PERIODIC_RECHECK;

    /**
     * 조사 판정에 맞는 전화 이유를 찾는다.
     *
     * @param classification 조사 판정. 조사에 실패한 Task 는 {@code null} 이다
     * @return 우선확인 → {@link #CHANGE_DETECTED}, 추가확인 → {@link #PERIODIC_RECHECK}.
     *         변화없음·조사 실패는 전화로 물을 것이 없어 비어 있다
     */
    public static Optional<CallReason> from(TaskClassification classification) {
        if (classification == null) {
            return Optional.empty();
        }
        return switch (classification) {
            case PRIORITY_CHECK -> Optional.of(CHANGE_DETECTED);
            case ADDITIONAL_CHECK -> Optional.of(PERIODIC_RECHECK);
            case NO_CHANGE -> Optional.empty();
        };
    }
}
