package com.ktc4.backend.domain.task.enums;

// DB 에는 이름 문자열("PRIORITY_CHECK" 등)로 저장된다. 순서(ordinal)로 저장하면 상수 순서를 바꾸는
// 순간 기존 데이터의 의미가 뒤바뀌므로, 사용하는 필드에는 반드시 @Enumerated(EnumType.STRING) 을 붙인다.
//
// 초안이던 TASK_HIGH/LOW/NONE을 멘토 리뷰(PR #25) 반영해 프론트 이름으로 통일했다 — HIGH/LOW는
// 무엇이 높고 낮은지(심각도? 우선순위?)를 드러내지 않아서다. Signal(SIGNAL_HIGH/LOW/NONE)과는
// 더 이상 이름 패턴을 맞추지 않는다. Task.classification을 실제로 어떻게 산출할지는 아직 정해지지
// 않았다 — 그 로직이 확정되면 이 주석도 같이 업데이트할 것.
public enum TaskClassification {
    PRIORITY_CHECK,    // 우선확인
    ADDITIONAL_CHECK,  // 추가확인
    NO_CHANGE          // 변화없음
}
