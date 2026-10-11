package com.ktc4.backend.domain.task.controller;

import com.ktc4.backend.domain.task.dto.TaskResultResponse;
import com.ktc4.backend.domain.verification.service.VerificationService;
import com.ktc4.backend.global.error.ApiProblemDetail;
import com.ktc4.backend.global.security.AuthMember;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "조사 결과 확인", description = "조사 결과 카드의 수정안을 반영하거나 확인 완료로 처리하는 API (관리자 전용)")
@RestController
@RequestMapping("/api/tasks")
@RequiredArgsConstructor
public class TaskController {

    private final VerificationService verificationService;

    @Operation(
            summary = "수정안 즉시 반영",
            description = """
                    결과 카드의 "즉시 수정 반영하기". 카드의 `proposedChanges` 를 **그대로 전부** 가게 정보에 반영하고,
                    가게 확인일(`lastCheckedAt`)과 확인 기록을 남깁니다. 응답은 결과 조회의 task 한 건과 같은 모양이라
                    그 카드만 바꿔 그리면 됩니다 (`verification` 이 채워져 있으면 "✓ 확인 완료됨").

                    - 카드 하나는 **한 번만** 처리합니다. 이미 반영·확인한 카드는 409 `task-already-confirmed`
                    - 수정안이 비었거나(변화없음·추가확인·조사 실패) 가게 규칙에 맞지 않는 값이 있으면
                      **아무것도 바꾸지 않고** 409 `nothing-to-apply` — "직접 수정하기"로 고친 뒤 확인 완료하세요
                    - 값을 고쳐서 반영하는 기능은 없습니다. 고치려면 `PATCH /api/stores/{storeId}` 뒤
                      `POST /api/tasks/{taskId}/confirm`
                    """)
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "반영됨 — 바뀐 가게 값과 확인 기록이 담긴 카드"),
            @ApiResponse(responseCode = "404", description = "taskId 에 해당하는 조사 결과가 없음 (task-not-found)",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = ApiProblemDetail.class))),
            @ApiResponse(responseCode = "409",
                    description = "이미 확인한 카드(task-already-confirmed), 반영할 수 있는 수정안이 없음(nothing-to-apply)",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = ApiProblemDetail.class))),
            @ApiResponse(responseCode = "401", description = "로그인하지 않음",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = ApiProblemDetail.class))),
            @ApiResponse(responseCode = "403", description = "관리자가 아님",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = ApiProblemDetail.class)))
    })
    @PostMapping("/{taskId}/apply")
    public TaskResultResponse apply(
            @Parameter(description = "조사 결과(Task) ID", example = "1") @PathVariable Long taskId,
            @AuthenticationPrincipal AuthMember admin) {
        return verificationService.apply(taskId, admin.memberId());
    }

    @Operation(
            summary = "확인 완료",
            description = """
                    결과 카드에서 연 "직접 수정하기" 창의 "자체 확인 완료". **가게 정보는 바꾸지 않고** 가게 확인일과
                    확인 기록(`action: NO_ACTION`)만 남깁니다. 값을 고쳤다면 `PATCH /api/stores/{storeId}` 로 먼저 저장하세요.

                    - 수정안이 없는 카드(변화없음·추가확인·조사 실패)도 확인 완료할 수 있습니다
                    - 이미 반영·확인한 카드는 409 `task-already-confirmed`
                    - 가게 목록에서 연 수정 창은 지금처럼 `POST /api/stores/{storeId}/confirm` 을 씁니다 (카드는 그대로)
                    """)
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "확인됨 — 확인 기록이 담긴 카드"),
            @ApiResponse(responseCode = "404", description = "taskId 에 해당하는 조사 결과가 없음 (task-not-found)",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = ApiProblemDetail.class))),
            @ApiResponse(responseCode = "409", description = "이미 확인한 카드 (task-already-confirmed)",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = ApiProblemDetail.class))),
            @ApiResponse(responseCode = "401", description = "로그인하지 않음",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = ApiProblemDetail.class))),
            @ApiResponse(responseCode = "403", description = "관리자가 아님",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = ApiProblemDetail.class)))
    })
    @PostMapping("/{taskId}/confirm")
    public TaskResultResponse confirm(
            @Parameter(description = "조사 결과(Task) ID", example = "1") @PathVariable Long taskId,
            @AuthenticationPrincipal AuthMember admin) {
        return verificationService.confirm(taskId, admin.memberId());
    }
}
