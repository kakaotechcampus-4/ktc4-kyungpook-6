package com.ktc4.backend.domain.task.enums;

// DB 에는 이름 문자열("TASK_HIGH" 등)로 저장된다. 순서(ordinal)로 저장하면 상수 순서를 바꾸는
// 순간 기존 데이터의 의미가 뒤바뀌므로, 사용하는 필드에는 반드시 @Enumerated(EnumType.STRING) 을 붙인다.
//
// Signal(SIGNAL_HIGH/LOW/NONE)과 같은 3단계 축이라 이름 패턴을 맞췄다. Task.classification을
// 실제로 어떻게 산출할지(예: 딸린 Signal들로부터 계산하는지 등)는 아직 정해지지 않았다 —
// 그 로직이 확정되면 이 주석도 같이 업데이트할 것. 팀 확인 필요: docs/도메인_용어집.md 참고.
public enum TaskClassification {
    TASK_HIGH,  // 우선확인
    TASK_LOW,   // 추가확인
    TASK_NONE   // 변화없음
}
