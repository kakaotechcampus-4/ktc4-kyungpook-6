package com.ktc4.backend.domain.signal.enums;

// Signal 이 가리키는 가게 항목. 한 가게에 Signal 이 여럿일 때 각 근거를 Task.proposedChanges 의
// 어느 항목과 짝지을지 정한다. proposedChanges 키와 AI 가 보내는 값은 괄호 안의 Store 필드명이다
// (상수 이름은 AI 쪽과 다를 수 있다 — AI 는 ADDRESS). DB 에는 상수 이름으로 저장한다(@Enumerated(EnumType.STRING)).
// 상수를 추가하면 운영 DB 의 signal_field_check 제약도 직접 고쳐야 한다 — ddl-auto=update 는 이 제약을 갱신하지 않는다.
public enum ChangeField {
    STATUS,        // 영업 상태 (status)
    PHONE,         // 전화번호 (phone)
    ADDRESS_ROAD,  // 도로명 주소 (addressRoad)
    NAME           // 상호 (name)
}
