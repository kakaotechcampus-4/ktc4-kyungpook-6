package com.ktc4.backend.domain.child.controller;

import com.ktc4.backend.domain.child.dto.ChildAuthResponse;
import com.ktc4.backend.domain.child.dto.ChildCardAuthRequest;
import com.ktc4.backend.domain.child.service.ChildAuthService;
import com.ktc4.backend.global.error.ApiProblemDetail;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "아동", description = "아동 급식카드 인증 API")
@RestController
@RequestMapping("/api/auth/children")
@RequiredArgsConstructor
public class ChildAuthController {

    private final ChildAuthService childAuthService;

    @Operation(
            summary = "아동 급식카드 인증",
            description = """
                    급식카드 번호로 인증하고 아동 토큰을 받습니다. 이후 아동 API(내 QR 발급 `POST /api/children/me/qr-token`)에
                    `Authorization: Bearer <accessToken>` 헤더를 붙입니다. 토큰은 `expiresAt`(7일 뒤)까지 유효하고,
                    지나면 카드로 다시 인증합니다. 응답 칸 이름은 점주 로그인과 같습니다(`role` 만 없음).

                    - 카드 번호는 숫자 16자리입니다. 숫자 사이에 공백·하이픈이 하나씩 있어도 됩니다(`1234 5678 1234 5678`).
                    - **같은 카드면 같은 아동**입니다. 처음 보는 카드면 아동이 새로 생깁니다 — 둘 다 200 이고 응답으로 구분하지 않습니다.
                    - ⚠️ 실제 급식카드인지는 **확인하지 않습니다**(카드사 연동이 없는 시연용). 형식만 봅니다.
                    - 서버는 카드 번호를 저장하지 않고, 비밀 키를 섞은 해시만 둡니다.

                    같은 곳(IPv4 는 주소 하나, IPv6 는 /64 대역)에서 10분에 20번, 하루 100번 넘게 부르면 429 `too-many-requests` 입니다
                    (형식이 틀려 거절된 요청도 셉니다). `Retry-After` 헤더의 초만큼 기다린 뒤 다시 보내세요.
                    """)
    @SecurityRequirements
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "인증 성공 — 아동 토큰"),
            @ApiResponse(responseCode = "400", description = "카드 번호가 없거나 숫자 16자리가 아닌 경우(errors 에 cardNumber 칸)",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = ApiProblemDetail.class))),
            @ApiResponse(responseCode = "429", description = "같은 곳에서 너무 자주 부른 경우(too-many-requests). Retry-After 헤더 참고",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = ApiProblemDetail.class))),
            @ApiResponse(responseCode = "503", description = "서버에 카드 인증 설정이 없어 지금 인증할 수 없는 경우(card-auth-unavailable)",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = ApiProblemDetail.class)))
    })
    @PostMapping("/card")
    public ChildAuthResponse authenticateByCard(@Valid @RequestBody ChildCardAuthRequest request) {
        return childAuthService.authenticate(request.cardNumber());
    }
}
