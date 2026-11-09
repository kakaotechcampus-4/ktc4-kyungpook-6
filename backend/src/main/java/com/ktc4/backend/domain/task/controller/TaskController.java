package com.ktc4.backend.domain.task.controller;

import com.ktc4.backend.domain.call.dto.CallResponse;
import com.ktc4.backend.domain.call.service.CallService;
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
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "조사 결과 확인", description = "조사 결과 카드의 수정안을 반영하거나, 가게에 전화로 확인하거나, 확인 완료로 처리하는 API (관리자 전용)")
@RestController
@RequestMapping("/api/tasks")
@RequiredArgsConstructor
public class TaskController {

    private final VerificationService verificationService;
    private final CallService callService;

    @Operation(
            summary = "수정안 즉시 반영",
            description = """
                    결과 카드의 "즉시 수정 반영하기". 카드의 `proposedChanges` 를 **그대로 전부** 가게 정보에 반영하고
                    확인 기록을 남깁니다. 응답은 결과 조회의 task 한 건과 같은 모양이라
                    그 카드만 바꿔 그리면 됩니다 (`verification` 이 채워져 있으면 "✓ 확인 완료됨").

                    **가게 확인일(`lastCheckedAt`)은 올라가지 않습니다.** 반영한 것은 조사가 찾아낸 값이지
                    담당자가 가게를 직접 본 것이 아니라서, 그 가게는 90일 재확인 대상에 그대로 남습니다.
                    확인일을 올리려면 `POST /api/tasks/{taskId}/confirm` 입니다.

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

    @Operation(
            summary = "가게에 전화로 확인",
            description = """
                    결과 카드의 "전화로 확인하기". 가게 전화번호로 **확인 전화를 요청**하고 전화 기록을 돌려줍니다.
                    통화가 끝나기를 기다리지 않고 바로 돌아옵니다 — 결과는 조사 결과를 다시 조회해 `call` 로 봅니다
                    (서버가 주기적으로 전화 API 에서 받아 와 갱신합니다).

                    **전화는 두 경우에만 걸 수 있습니다.**

                    - `PRIORITY_CHECK`(우선확인) — 조사가 찾아낸 값이 맞는지 항목마다 확인합니다
                    - `ADDITIONAL_CHECK`(추가확인) — 등록된 값이 그대로인지 한 번에 묻습니다 (확인 후 90일 이상)

                    변화도 없고 최근에 확인한 결과(`NO_CHANGE`)와 조사에 실패한 결과는 물을 것이 없어 409 입니다.

                    **전화 한 통이 담당자 확인을 대신하지 않습니다.** 가게 확인일(`lastCheckedAt`)은 올라가지 않고
                    확인 기록(`verification`)도 생기지 않습니다 — 점주의 답을 읽고 판단하는 일은 담당자가 합니다.
                    특히 영업 여부는 "지금도 영업하십니까" 한 가지만 묻기 때문에, 휴업과 폐업을 가르는 일은 담당자 몫입니다.

                    - 같은 카드에 전화는 **한 번만** 들어갑니다. 걸어 둔 전화가 있으면 409 `call-already-requested`.
                      **실패한 전화는 다시 걸 수 있습니다**(안 받았거나 끊긴 통화)
                    - 이미 확인을 끝낸 카드, 가게 전화번호가 없는 가게, 물을 질문이 만들어지지 않는 경우는
                      409 `call-target-not-eligible`
                    - 전화 API 주소·키를 아직 넣지 않은 환경은 503 `call-not-available`
                    """)
    @ApiResponses(value = {
            @ApiResponse(responseCode = "202", description = "요청이 들어감 — 전화 기록"),
            @ApiResponse(responseCode = "404", description = "taskId 에 해당하는 조사 결과가 없음 (task-not-found)",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = ApiProblemDetail.class))),
            @ApiResponse(responseCode = "409",
                    description = "전화로 확인할 대상이 아님(call-target-not-eligible), 이미 전화를 요청함(call-already-requested)",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = ApiProblemDetail.class))),
            @ApiResponse(responseCode = "502", description = "전화 API 가 거절했거나 닿지 않음 (call-api-error)",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = ApiProblemDetail.class))),
            @ApiResponse(responseCode = "503", description = "전화 API 주소·키가 없음 (call-not-available)",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = ApiProblemDetail.class))),
            @ApiResponse(responseCode = "401", description = "로그인하지 않음",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = ApiProblemDetail.class))),
            @ApiResponse(responseCode = "403", description = "관리자가 아님",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = ApiProblemDetail.class)))
    })
    @ResponseStatus(HttpStatus.ACCEPTED)
    @PostMapping("/{taskId}/call")
    public CallResponse call(
            @Parameter(description = "조사 결과(Task) ID", example = "1") @PathVariable Long taskId,
            @AuthenticationPrincipal AuthMember admin) {
        return callService.request(taskId, admin.memberId());
    }
}
