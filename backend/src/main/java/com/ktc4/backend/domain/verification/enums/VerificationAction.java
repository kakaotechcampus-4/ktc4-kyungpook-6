package com.ktc4.backend.domain.verification.enums;

// DB 에는 이름 문자열("UPDATE_INFO" 등)로 저장된다. 순서(ordinal)로 저장하면 상수 순서를 바꾸는
// 순간 기존 데이터의 의미가 뒤바뀌므로, 사용하는 필드에는 반드시 @Enumerated(EnumType.STRING) 을 붙인다.
public enum VerificationAction {
    UPDATE_INFO,    // 정보수정
    CHANGE_STATUS,  // 상태변경
    NO_ACTION       // 조치없음
}
