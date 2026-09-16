package com.ktc4.backend.domain.signal.enums;

// DB 에는 이름 문자열("SIGNAL_HIGH" 등)로 저장된다. 순서(ordinal)로 저장하면 상수 순서를 바꾸는
// 순간 기존 데이터의 의미가 뒤바뀌므로, 사용하는 필드에는 반드시 @Enumerated(EnumType.STRING) 을 붙인다.
public enum SignalType {
    SIGNAL_HIGH,  // 우선검토필요
    SIGNAL_LOW,   // 추가검토권장
    SIGNAL_NONE   // 변화근거부족
}
