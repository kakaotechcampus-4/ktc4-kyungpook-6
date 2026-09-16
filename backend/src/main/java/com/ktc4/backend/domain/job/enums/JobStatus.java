package com.ktc4.backend.domain.job.enums;

// DB 에는 이름 문자열("FAILED" 등)로 저장된다. 순서(ordinal)로 저장하면 상수 순서를 바꾸는 순간
// 기존 데이터의 의미가 뒤바뀌므로, 사용하는 필드에는 반드시 @Enumerated(EnumType.STRING) 을 붙인다.
public enum JobStatus {
    PENDING,      // 대기 중
    IN_PROGRESS,  // 진행 중
    DONE,         // 완료
    FAILED        // 실패
}
