package com.ktc4.backend.domain.task.dto;

import com.ktc4.backend.domain.store.enums.StoreStatus;
import com.ktc4.backend.domain.task.enums.TaskClassification;
import com.ktc4.backend.domain.verification.dto.VerificationResponse;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.List;

/** 조사 결과의 가게 한 곳 (Task 한 행 + 가게 정보 + 근거). */
public record TaskResultResponse(
        @Schema(description = "Task ID", example = "1")
        Long taskId,

        @Schema(description = "가게 ID", example = "5")
        Long storeId,

        @Schema(description = "상호", example = "예시분식")
        String storeName,

        @Schema(description = "도로명 주소", example = "대구광역시 북구 대학로 80")
        String storeAddress,

        @Schema(description = "우리 DB 의 지금 영업 상태", example = "OPEN")
        StoreStatus storeStatus,

        @Schema(description = "우리 DB 의 지금 전화번호", nullable = true, example = "053-111-1111")
        String storePhone,

        @Schema(description = "담당자가 마지막으로 직접 확인한 시각", nullable = true)
        LocalDateTime lastCheckedAt,

        @Schema(description = "판정. PRIORITY_CHECK(우선확인) · ADDITIONAL_CHECK(추가확인) · NO_CHANGE(변화없음). "
                + "**null 이면 조사 실패** — failureReason 에 이유가 있다", nullable = true, example = "PRIORITY_CHECK")
        TaskClassification classification,

        @Schema(description = "조사 실패 이유. 성공이면 null", nullable = true)
        String failureReason,

        @Schema(description = "수정안. 변화가 없거나 실패면 빈 목록")
        List<ProposedChangeResponse> proposedChanges,

        @Schema(description = "수정안의 근거. 변화가 없거나 실패면 빈 목록")
        List<EvidenceResponse> evidences,

        @Schema(description = "담당자의 확인 기록. **있으면 카드가 \"확인 완료됨\"**, 아직 확인하지 않았으면 null",
                nullable = true)
        VerificationResponse verification
) {

    /** 확인 기록만 바꾼 새 응답을 만든다. 조사 결과와 확인 기록은 서로 다른 서비스가 읽어 마지막에 합친다. */
    public TaskResultResponse withVerification(VerificationResponse verification) {
        return new TaskResultResponse(taskId, storeId, storeName, storeAddress, storeStatus, storePhone, lastCheckedAt,
                classification, failureReason, proposedChanges, evidences, verification);
    }
}
