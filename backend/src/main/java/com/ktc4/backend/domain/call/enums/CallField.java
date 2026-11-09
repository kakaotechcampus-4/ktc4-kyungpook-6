package com.ktc4.backend.domain.call.enums;

import com.ktc4.backend.domain.signal.enums.ChangeField;

import java.util.Arrays;
import java.util.Optional;

/**
 * 전화로 물을 수 있는 항목. 우리 쪽 항목({@link ChangeField})과 전화 API 의 항목 키를 잇는다.
 *
 * <p>이름이 셋인 이유가 각각 다르다.
 *
 * <ul>
 *   <li>{@code apiField} — 전화 API 가 요청·응답에서 쓰는 키. 답을 우리 항목으로 되돌릴 때 이것으로 찾는다.
 *       {@code storeName}·{@code storeLocation} 은 전화 API 문서의 예시와 같은 값이다</li>
 *   <li>{@code naturalField} — 통화 중에 사람에게 항목을 부를 때 쓰는 말. 점주가 듣는 말이라
 *       코드 이름이 아니라 가게에서 쓰는 말로 적는다</li>
 *   <li>상수 이름 — 우리 코드가 부르는 이름</li>
 * </ul>
 *
 * <p>영업 여부({@link #OPEN})만 참·거짓으로 묻는다. 우리는 영업 상태를 {@code OPEN}·{@code SUSPENDED}·
 * {@code CLOSED} 세 값으로 갖지만, 전화로 "상태가 CLOSED 맞습니까" 를 물을 수는 없다. 그래서 전화에는
 * "지금도 영업하십니까" 한 가지만 묻고, 휴업과 폐업을 가르는 일은 담당자가 답을 보고 한다.
 *
 * <p>이 질문이 통화에서 어떻게 들리는지 2026-11-04 에 실제로 걸어 확인했다 — 어색하지 않았다.
 */
public enum CallField {
    STORE_NAME(ChangeField.NAME, "storeName", "상호명"),
    STORE_LOCATION(ChangeField.ADDRESS_ROAD, "storeLocation", "매장 위치"),
    PHONE_NUMBER(ChangeField.PHONE, "phoneNumber", "전화번호"),
    OPEN(ChangeField.STATUS, "open", "영업 여부");

    private final ChangeField changeField;
    private final String apiField;
    private final String naturalField;

    CallField(ChangeField changeField, String apiField, String naturalField) {
        this.changeField = changeField;
        this.apiField = apiField;
        this.naturalField = naturalField;
    }

    /** 우리 쪽 항목. 수정안(`proposedChanges`)의 키를 여기서 얻는다. */
    public ChangeField changeField() {
        return changeField;
    }

    /** 전화 API 요청·응답의 항목 키. */
    public String apiField() {
        return apiField;
    }

    /** 통화 중에 항목을 부를 때 쓰는 이름. */
    public String naturalField() {
        return naturalField;
    }

    /** 참·거짓으로 묻는 항목인가. 참이면 값을 문자열이 아니라 JSON 불리언으로 보낸다. */
    public boolean isBoolean() {
        return this == OPEN;
    }

    /**
     * 우리 항목에 맞는 전화 항목을 찾는다.
     *
     * @param changeField 우리 쪽 항목
     * @return 해당 상수. 전화로 물을 수 없는 항목이면 비어 있다
     */
    public static Optional<CallField> of(ChangeField changeField) {
        return Arrays.stream(values())
                .filter(field -> field.changeField == changeField)
                .findFirst();
    }

    /**
     * 전화 API 가 답에 적어 보낸 키로 상수를 찾는다.
     *
     * @param apiField 전화 API 의 항목 키
     * @return 해당 상수. 우리가 묻지 않은 키면 비어 있다
     */
    public static Optional<CallField> fromApiField(String apiField) {
        return Arrays.stream(values())
                .filter(field -> field.apiField.equals(apiField))
                .findFirst();
    }
}
