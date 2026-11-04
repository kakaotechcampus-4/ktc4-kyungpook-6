package com.ktc4.backend.domain.call.dto;

import com.ktc4.backend.domain.call.entity.VerificationCall;
import com.ktc4.backend.domain.call.enums.CallReason;
import com.ktc4.backend.domain.call.enums.CallStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 조사 결과 카드 한 장에 붙는 확인 전화 기록.
 *
 * <p><b>담당자 확인 기록({@code verification})과 다른 것입니다.</b> 전화는 점주에게 물어본 결과이고,
 * 확인 기록은 담당자가 그 답을 읽고 남긴 판단입니다. 전화 한 통으로 가게 확인일은 올라가지 않습니다.
 */
public record CallResponse(
        @Schema(description = "전화 기록 ID", example = "7")
        Long callId,

        @Schema(description = "PENDING(대기·발신 준비) · IN_PROGRESS(발신·통화 중) · COMPLETED(확인 마치고 종료) · "
                + "FAILED(연결 실패·확인 미완료·오류). 끝나지 않은 전화는 서버가 주기적으로 갱신하므로 "
                + "화면은 결과를 다시 조회하면 됩니다", example = "COMPLETED")
        CallStatus status,

        @Schema(description = "왜 걸었는가. CHANGE_DETECTED(변화가 잡혀서) · PERIODIC_RECHECK(90일 넘게 확인하지 않아서)",
                example = "CHANGE_DETECTED")
        CallReason reason,

        @Schema(description = "전화를 요청한 시각")
        LocalDateTime requestedAt,

        @Schema(description = "통화 처리가 끝난 시각. 진행 중이면 null", nullable = true)
        LocalDateTime endedAt,

        @Schema(description = "실패 사유. 실패가 아니면 null", nullable = true, example = "NO_ANSWER")
        String failureReason,

        @Schema(description = "통화에서 들은 답. 진행 중이거나 실패한 통화의 부분 결과도 들어옵니다")
        List<CallAnswerResponse> answers
) {

    public static CallResponse from(VerificationCall call) {
        List<CallAnswerResponse> answers = call.getAnswers() == null
                ? List.of()
                : call.getAnswers().stream().map(CallAnswerResponse::from).toList();
        return new CallResponse(
                call.getCallId(), call.getStatus(), call.getReason(),
                call.getRequestedAt(), call.getEndedAt(), call.getFailureReason(), answers);
    }
}
