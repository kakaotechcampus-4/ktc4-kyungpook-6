package com.ktc4.backend.domain.call.enums;

/**
 * 확인 전화 한 건의 진행 상태. 값과 이름은 전화 API 의 {@code status} 를 그대로 쓴다 —
 * 우리가 다시 이름 붙이면 전화 API 문서와 우리 화면이 다른 말을 하게 된다.
 *
 * <p>DB 에는 이름 문자열로 저장한다({@code @Enumerated(EnumType.STRING)}).
 * 상수를 추가하면 운영 DB 의 {@code verification_call_status_check} 제약도 함께 고쳐야 한다 —
 * 스키마는 {@code db/migration} 의 SQL 이 바꾼다.
 */
public enum CallStatus {
    PENDING,      // 통화 대기 또는 발신 준비 중
    IN_PROGRESS,  // 발신, 연결 대기 또는 통화 처리 중
    COMPLETED,    // 필요한 확인을 마치고 통화 종료
    FAILED;       // 연결 실패, 확인 미완료 또는 처리 오류로 종료

    /** 더 기다릴 것이 없는 상태인가. 폴러는 이 상태가 아닌 전화만 전화 API 에 다시 물어본다. */
    public boolean isFinished() {
        return this == COMPLETED || this == FAILED;
    }
}
