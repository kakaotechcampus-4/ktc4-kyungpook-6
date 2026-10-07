package com.ktc4.backend.domain.member.controller;

import com.ktc4.backend.domain.member.dto.OwnerApplicationResponse;
import com.ktc4.backend.domain.member.dto.OwnerApproveRequest;
import com.ktc4.backend.domain.member.dto.StoreCandidateResponse;
import com.ktc4.backend.domain.member.enums.MemberStatus;
import com.ktc4.backend.domain.member.service.OwnerApprovalService;
import com.ktc4.backend.global.dto.PageResponse;
import com.ktc4.backend.global.error.ApiProblemDetail;
import com.ktc4.backend.global.security.AuthMember;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "점주 가입 관리(관리자)", description = "관리자가 점주 가입 신청을 확인·승인하는 API")
@RestController
@RequestMapping("/api/admin/owners")
@RequiredArgsConstructor
public class OwnerAdminController {

    private static final int MAX_LIMIT = 100;

    private final OwnerApprovalService ownerApprovalService;

    @Operation(summary = "점주 가입 신청 목록",
            description = "점주 가입 신청을 신청 순서대로 조회합니다. 상태를 주지 않으면 승인 대기(PENDING) 목록입니다. 관리자만 부를 수 있습니다.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "400", description = "status 값이 잘못됐거나 page·limit 이 범위를 벗어난 경우",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = ApiProblemDetail.class)))
    })
    @GetMapping
    public PageResponse<OwnerApplicationResponse> getApplications(
            @Parameter(description = "PENDING(승인 대기) / APPROVED(승인됨) / REJECTED(거절됨)", example = "PENDING")
            @RequestParam(defaultValue = "PENDING") MemberStatus status,
            @Parameter(description = "페이지 번호(0부터 시작)", example = "0")
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @Parameter(description = "한 페이지당 건수 (1~100)", example = "20")
            @RequestParam(defaultValue = "20") @Min(1) @Max(MAX_LIMIT) int limit) {

        return ownerApprovalService.getApplications(status, page, limit);
    }

    @Operation(summary = "후보 가게 조회",
            description = """
                    가입 신청한 점주에게 연결할 후보 가게를 찾습니다. 관리자가 이 중 하나를 골라 승인 API 에 넘깁니다.

                    - 신청서의 사업자등록번호가 같은 가게(`bizNoMatched: true`), 휴대폰 번호가 가게 전화번호와 같은 가게(`phoneMatched: true`),
                      상호명이 겹치는 가게 순서로 옵니다. 최대 20곳입니다. 번호는 하이픈·공백을 빼고 비교합니다.
                    - `linkedOwnerCount` 가 1 이상이면 이미 점주가 연결된 가게입니다. 재가입·공동 대표·양수인지, 사칭인지 확인해 주세요.
                    - 후보가 없으면 빈 배열입니다. 가게 목록에서 직접 찾은 가게 ID 로도 승인할 수 있습니다.
                    """)
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "조회 성공 (후보가 없으면 빈 배열)"),
            @ApiResponse(responseCode = "404", description = "해당 ID 의 점주가 없는 경우",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = ApiProblemDetail.class)))
    })
    @GetMapping("/{memberId}/store-candidates")
    public List<StoreCandidateResponse> getStoreCandidates(
            @Parameter(description = "가입 신청한 회원 ID", example = "3") @PathVariable Long memberId) {
        return ownerApprovalService.getStoreCandidates(memberId);
    }

    @Operation(summary = "점주 가입 승인",
            description = """
                    승인 대기 중인 점주를 승인하고, 고른 가게에 연결합니다. 승인된 점주는 바로 로그인해 그 가게에 체크인할 수 있습니다.
                    관리자만 부를 수 있습니다.

                    - `storeId` 는 필수입니다. 가게 없이는 승인할 수 없습니다.
                    - 승인과 가게 연결은 함께 처리됩니다. 가게가 없으면 신청은 승인 대기 그대로 남습니다.
                    """)
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "승인 성공"),
            @ApiResponse(responseCode = "400", description = "storeId 가 없거나 본문이 비어 있는 경우",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = ApiProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "해당 ID 의 점주가 없거나(member-not-found), 가게가 없는 경우(store-not-found)",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = ApiProblemDetail.class))),
            @ApiResponse(responseCode = "409", description = "이미 승인·거절된 신청인 경우",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = ApiProblemDetail.class)))
    })
    @PostMapping("/{memberId}/approve")
    public OwnerApplicationResponse approve(
            @Parameter(description = "승인할 회원 ID", example = "3") @PathVariable Long memberId,
            @Valid @RequestBody OwnerApproveRequest request,
            @AuthenticationPrincipal AuthMember admin) {
        return ownerApprovalService.approve(memberId, request.storeId(), admin.memberId());
    }
}
