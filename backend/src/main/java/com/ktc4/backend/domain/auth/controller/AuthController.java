package com.ktc4.backend.domain.auth.controller;

import com.ktc4.backend.domain.auth.dto.LoginRequest;
import com.ktc4.backend.domain.auth.dto.LoginResponse;
import com.ktc4.backend.domain.auth.dto.MemberResponse;
import com.ktc4.backend.domain.auth.dto.OwnerSignupRequest;
import com.ktc4.backend.domain.auth.service.AuthService;
import com.ktc4.backend.global.error.ApiProblemDetail;
import com.ktc4.backend.global.security.AuthMember;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "인증", description = "관리자·점주 로그인과 점주 가입 신청 API")
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @Operation(
            summary = "로그인",
            description = """
                    이메일·비밀번호로 로그인하고 접근 토큰을 받습니다. 이후 요청에는
                    `Authorization: Bearer <accessToken>` 헤더를 붙입니다. 토큰은 `expiresAt` 까지 유효합니다.

                    점주는 관리자가 가입을 승인한 뒤에만 로그인할 수 있습니다.
                    """)
    @SecurityRequirements
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "로그인 성공"),
            @ApiResponse(responseCode = "400", description = "이메일·비밀번호가 비었거나 형식이 틀린 경우",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = ApiProblemDetail.class))),
            @ApiResponse(responseCode = "401", description = "이메일 또는 비밀번호가 틀린 경우 (둘 중 무엇이 틀렸는지는 알려주지 않습니다)",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = ApiProblemDetail.class))),
            @ApiResponse(responseCode = "403", description = "승인 대기 중이거나 가입이 거절된 점주",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = ApiProblemDetail.class)))
    })
    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request);
    }

    @Operation(
            summary = "점주 가입 신청",
            description = """
                    점주 계정을 신청합니다. 관리자가 승인하기 전까지는 로그인할 수 없습니다
                    (로그인하면 403 `owner-pending-approval`).

                    사업자등록번호는 하이픈이 있어도 되고, 서버가 숫자 10자리로 맞춰 저장합니다.
                    휴대폰 번호(`phone`)도 하이픈이 있어도 되며 010·011·016·017·018·019 로 시작해야 합니다.
                    가게를 등록할 때 적은 번호와 같으면 관리자가 가게를 찾기 쉬워집니다.
                    """)
    @SecurityRequirements
    @ApiResponses(value = {
            @ApiResponse(responseCode = "201", description = "신청 완료 — 상태는 PENDING(승인 대기)"),
            @ApiResponse(responseCode = "400",
                    description = "입력값이 비었거나 형식이 틀린 경우(errors 에 필드 표시), 사업자등록번호가 10자리가 아닌 경우(invalid-biz-no), "
                            + "휴대폰 번호 형식이 아닌 경우(invalid-phone)",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = ApiProblemDetail.class))),
            @ApiResponse(responseCode = "409", description = "이미 가입된 이메일",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = ApiProblemDetail.class)))
    })
    @PostMapping("/owners/signup")
    @ResponseStatus(HttpStatus.CREATED)
    public MemberResponse signupOwner(@Valid @RequestBody OwnerSignupRequest request) {
        return authService.signupOwner(request);
    }

    @Operation(summary = "내 정보 조회", description = "토큰의 주인이 누구인지 확인합니다. 앱을 다시 열었을 때 토큰이 아직 유효한지 확인하는 데도 씁니다.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "401", description = "토큰이 없거나 만료·위조된 경우",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = ApiProblemDetail.class)))
    })
    @GetMapping("/me")
    public MemberResponse me(@AuthenticationPrincipal AuthMember member) {
        return authService.getMe(member.memberId());
    }
}
