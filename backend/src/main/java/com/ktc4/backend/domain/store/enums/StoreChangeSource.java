package com.ktc4.backend.domain.store.enums;

/**
 * 가게 정보가 어느 경로로 바뀌었는지. 역할이 아니라 경로로 나눈다 — 관리자도 가게 관리 저장을 부를 수 있고,
 * 누가 바꿨는지는 변경 기록의 {@code changedBy} 가 따로 말한다.
 *
 * <p>⚠️ 값을 늘리면 DB 의 CHECK 제약을 직접 고쳐야 한다({@link StoreChangeField} 와 같은 이유).
 */
public enum StoreChangeSource {
    /** 관리자 가게 수정 ({@code PATCH /api/stores/{storeId}}) */
    STORE_EDIT,
    /** 조사 수정안 반영 ({@code POST /api/tasks/{taskId}/apply}) — 어느 조사 결과인지 같이 남긴다 */
    TASK_APPLY,
    /** 점주 승인·다시 연결 때 가입 신청서의 가게 정보를 빈 항목에 옮겨 적기 */
    SIGNUP_FILL,
    /** 가게 관리 저장 ({@code PUT /api/stores/{storeId}/operating-info}) */
    OPERATING_INFO_SAVE
}
