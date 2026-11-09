package com.ktc4.backend.domain.call.dto;

import com.ktc4.backend.domain.call.entity.CallAnswer;
import com.ktc4.backend.domain.call.enums.CallStatus;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 전화 API 에 지금 상태를 물어 받은 답. 시각은 우리 시계대로 옮겨 적힌 값이다.
 *
 * @param status        지금 상태
 * @param answers       확인된 값들. 진행 중·실패한 통화의 부분 결과도 들어온다. 없으면 빈 목록
 * @param startedAt     통화 처리를 시작한 시각. 시작 전이면 null
 * @param connectedAt   상대와 연결된 시각. 연결 전이거나 실패했으면 null
 * @param endedAt        통화 처리가 끝난 시각. 진행 중이면 null
 * @param failureReason 실패 사유 코드. 없으면 null
 */
public record CallSnapshot(CallStatus status, List<CallAnswer> answers,
                           LocalDateTime startedAt, LocalDateTime connectedAt, LocalDateTime endedAt,
                           String failureReason) {

    public CallSnapshot {
        answers = answers == null ? List.of() : List.copyOf(answers);
    }
}
