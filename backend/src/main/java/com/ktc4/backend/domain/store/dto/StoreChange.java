package com.ktc4.backend.domain.store.dto;

import com.ktc4.backend.domain.store.enums.StoreChangeSource;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 가게 정보를 바꾸는 한 번의 요청 — 어느 경로로, 누가, 언제. 이 요청으로 바뀐 항목마다 변경 기록이 한 줄씩 남고,
 * 그 줄들은 이 값을 함께 쓴다.
 *
 * <p>경로마다 정적 메서드로만 만든다. 조사 결과 ID 는 <b>조사 결과에서 비롯된 경로</b>에만 있다 —
 * 생성할 때 막는다.
 *
 * @param source    바꾼 경로
 * @param changedBy 바꾼 회원 ID (관리자 또는 점주)
 * @param taskId    조사 결과 ID. {@link StoreChangeSource#TASK_APPLY}(수정안을 그대로 반영)와
 *                  {@link StoreChangeSource#CALL_APPLY}(그 결과를 보고 건 전화의 답을 반영)에만 있다.
 *                  확인 전화는 조사 결과 없이도 걸 수 있어 {@code CALL_APPLY} 는 비어 있을 수 있다
 * @param changedAt 요청을 처리한 시각. 같은 가게에 동시에 온 변경의 순서는 이 값이 아니라 기록 ID 로 본다
 */
public record StoreChange(StoreChangeSource source, Long changedBy, Long taskId, LocalDateTime changedAt) {

    public StoreChange {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(changedBy, "changedBy");
        Objects.requireNonNull(changedAt, "changedAt");
        /*
          조사 수정안 반영은 반드시 어느 결과인지 있어야 하고, 확인 전화는 조사 결과 없이도 걸 수 있어
          있을 수도 없을 수도 있다. 그 둘 말고는 조사 결과 ID 를 달 자리가 없다.
        */
        if (source == StoreChangeSource.TASK_APPLY && taskId == null) {
            throw new IllegalArgumentException("조사 수정안 반영에는 조사 결과 ID 가 있어야 한다");
        }
        if (taskId != null
                && source != StoreChangeSource.TASK_APPLY
                && source != StoreChangeSource.CALL_APPLY) {
            throw new IllegalArgumentException("조사 결과 ID 는 조사 결과에서 비롯된 경로에만 있다 - source="
                    + source + ", taskId=" + taskId);
        }
    }

    /**
     * 관리자 가게 수정.
     *
     * @param adminId 관리자 회원 ID
     * @return 지금 시각의 변경
     */
    public static StoreChange storeEdit(Long adminId) {
        return new StoreChange(StoreChangeSource.STORE_EDIT, adminId, null, LocalDateTime.now());
    }

    /**
     * 조사 수정안 반영. 시각은 확인 기록과 같은 값을 쓰도록 호출자가 넘긴다.
     *
     * @param adminId   관리자 회원 ID
     * @param taskId    조사 결과 ID
     * @param changedAt 반영 시각 (확인 기록의 확인 시각과 같은 값)
     * @return 변경
     */
    public static StoreChange taskApply(Long adminId, Long taskId, LocalDateTime changedAt) {
        return new StoreChange(StoreChangeSource.TASK_APPLY, adminId, taskId, changedAt);
    }

    /**
     * 점주 승인·다시 연결 때 가입 신청서를 옮겨 적기.
     *
     * @param adminId 승인·연결한 관리자 회원 ID
     * @return 지금 시각의 변경
     */
    public static StoreChange signupFill(Long adminId) {
        return new StoreChange(StoreChangeSource.SIGNUP_FILL, adminId, null, LocalDateTime.now());
    }

    /**
     * 가게 관리 저장.
     *
     * @param memberId 저장한 회원 ID (점주 또는 관리자)
     * @return 지금 시각의 변경
     */
    public static StoreChange operatingInfoSave(Long memberId) {
        return new StoreChange(StoreChangeSource.OPERATING_INFO_SAVE, memberId, null, LocalDateTime.now());
    }

    /**
     * 확인 전화 결과를 자동으로 반영할 때.
     *
     * @param requestedBy 그 전화를 요청한 관리자 ID. 값을 바꾼 것은 통화지만, 전화를 건 사람은 이 사람이다
     * @param taskId      그 전화를 띄운 조사 결과 ID. 조사 결과 없이 건 전화면 {@code null}
     * @param changedAt   바꾼 시각 — 전화 기록과 같은 시각을 쓴다
     */
    public static StoreChange callApply(Long requestedBy, Long taskId, LocalDateTime changedAt) {
        return new StoreChange(StoreChangeSource.CALL_APPLY, requestedBy, taskId, changedAt);
    }
}
