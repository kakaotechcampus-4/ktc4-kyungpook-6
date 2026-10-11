package com.ktc4.backend.domain.signal.enums;

// 이 Signal 을 어느 조사가 잡았는지. 한 Task 에 국세청·웹 신호가 같이 붙을 수 있어(폐업인데 이전 개업)
// Task 가 아니라 Signal 에 둔다. DB 에는 이름 문자열로 저장한다(@Enumerated(EnumType.STRING)).
// 상수를 추가하면 운영 DB 의 signal_source_check 제약도 직접 고쳐야 한다 — ddl-auto=update 는 이 제약을 갱신하지 않는다.
public enum SignalSource {
    NTS,     // 1차 조사 — 국세청 사업자 상태 대조
    AI_WEB   // 2차 조사 — AI 웹검색
}
