package com.ktc4.backend.domain.store.enums;

// DB 에는 이름 문자열("CLOSED" 등)로 저장된다. 순서(ordinal)로 저장하면 상수 순서를 바꾸는 순간
// 기존 데이터의 의미가 뒤바뀌므로, 사용하는 필드에는 반드시 @Enumerated(EnumType.STRING) 을 붙인다.
public enum StoreStatus {
    OPEN,       // 영업중
    SUSPENDED,  // 휴업
    CLOSED,     // 폐업
    UNKNOWN     // 미확인 — 아직 확인되지 않았거나 확인에 실패한 상태
}
