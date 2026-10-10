package com.ktc4.backend.domain.signal.enums;

import java.util.Arrays;
import java.util.Optional;

// Signal 이 가리키는 가게 항목. 한 가게에 Signal 이 여럿일 때 각 근거를 Task.proposedChanges 의
// 어느 항목과 짝지을지 정한다. proposedChanges 키와 AI 가 보내는 값은 key 의 Store 필드명이다
// (상수 이름은 AI 쪽과 다를 수 있다 — AI 는 ADDRESS). DB 에는 상수 이름으로 저장한다(@Enumerated(EnumType.STRING)).
// 상수를 추가하면 운영 DB 의 signal_field_check 제약도 직접 고쳐야 한다 — ddl-auto=update 는 이 제약을 갱신하지 않는다.
public enum ChangeField {
    STATUS("status"),             // 영업 상태
    PHONE("phone"),               // 전화번호
    ADDRESS_ROAD("addressRoad"),  // 도로명 주소
    NAME("name");                 // 상호

    // proposedChanges 의 키이자 AI 가 보내는 field 값. 키 ↔ 상수 변환은 여기 한 곳에서만 한다.
    private final String key;

    ChangeField(String key) {
        this.key = key;
    }

    /** proposedChanges 키(Store 필드명). 예: {@code ADDRESS_ROAD} → {@code "addressRoad"} */
    public String key() {
        return key;
    }

    /**
     * proposedChanges 키로 상수를 찾는다.
     *
     * @param key Store 필드명 (예: {@code "phone"})
     * @return 해당 상수. 모르는 키나 {@code null} 이면 비어 있다
     */
    public static Optional<ChangeField> fromKey(String key) {
        return Arrays.stream(values())
                .filter(field -> field.key.equals(key))
                .findFirst();
    }
}
