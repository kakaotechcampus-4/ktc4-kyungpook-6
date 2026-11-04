package com.ktc4.backend.domain.call.dto;

/**
 * 전화에서 묻는 방식. 값은 전화 API 의 {@code questions[].type} 과 같다.
 *
 * <p>둘을 가르는 것은 "우리가 답을 미리 알고 있는지" 다.
 *
 * <ul>
 *   <li>{@link #CHANGE_DETECTION} — 등록된 값이 바뀌었는지만 묻는다. 90일 재확인처럼 짚을 데가 없을 때 쓴다</li>
 *   <li>{@link #VALUE_CONFIRMATION} — 조사가 찾아낸 값이 맞는지 묻는다. 변화가 잡혀 확인할 값이 있을 때 쓴다</li>
 * </ul>
 */
public enum CallQuestionType {
    CHANGE_DETECTION,
    VALUE_CONFIRMATION
}
