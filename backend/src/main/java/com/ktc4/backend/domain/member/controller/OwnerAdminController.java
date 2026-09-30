package com.ktc4.backend.domain.member.controller;

import com.ktc4.backend.domain.member.dto.OwnerApplicationResponse;
import com.ktc4.backend.domain.member.enums.MemberStatus;
import com.ktc4.backend.domain.member.service.OwnerApprovalService;
import com.ktc4.backend.global.dto.PageResponse;
import com.ktc4.backend.global.error.ApiProblemDetail;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

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

    @Operation(summary = "점주 가입 승인",
            description = "승인 대기 중인 점주를 승인합니다. 승인된 점주는 바로 로그인할 수 있습니다. 관리자만 부를 수 있습니다.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "승인 성공"),
            @ApiResponse(responseCode = "404", description = "해당 ID 의 점주가 없는 경우",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = ApiProblemDetail.class))),
            @ApiResponse(responseCode = "409", description = "이미 승인·거절된 신청인 경우",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = ApiProblemDetail.class)))
    })
    @PostMapping("/{memberId}/approve")
    public OwnerApplicationResponse approve(
            @Parameter(description = "승인할 회원 ID", example = "3") @PathVariable Long memberId) {
        return ownerApprovalService.approve(memberId);
    }
}
