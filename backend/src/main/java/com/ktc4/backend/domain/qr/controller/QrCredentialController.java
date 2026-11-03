package com.ktc4.backend.domain.qr.controller;

import com.ktc4.backend.domain.qr.dto.QrTokenResponse;
import com.ktc4.backend.domain.qr.service.QrCredentialService;
import com.ktc4.backend.global.error.ApiProblemDetail;
import com.ktc4.backend.global.security.AuthMember;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "QR", description = "아동 QR 발급 API")
@RestController
@RequestMapping("/api/children")
@RequiredArgsConstructor
public class QrCredentialController {

    private final QrCredentialService qrCredentialService;

    @Operation(
            summary = "내 QR 발급 (아동)",
            description = """
                    급식카드로 인증한 아동이 자기 QR 에 담을 문자열(`v1.<토큰>`)을 받습니다. 아동 토큰
                    (`POST /api/auth/children/card` 의 `accessToken`)을 `Authorization: Bearer` 로 붙입니다.
                    어느 아동인지는 토큰으로 정하고, 요청으로 아동 번호를 받지 않습니다.

                    - 이 값은 **이 응답에서 한 번만** 나갑니다. 서버는 해시만 보관해서 다시 알려줄 수 없습니다.
                    - **부를 때마다 새 QR 이고, 옛 QR 은 바로 쓸 수 없게 됩니다**(QR 화면이 유출됐을 때 이렇게 막습니다).
                      그래서 앱은 받은 값을 **기기에 저장해 두고 QR 이미지로 그려 보여 주세요.** 화면을 열 때마다 부르면
                      점주가 찍는 사이에 QR 이 바뀔 수 있습니다. 보여줄 때는 인터넷이 필요 없습니다.
                    - 버튼을 빠르게 두 번 누르는 등 동시에 여러 번 부르면 **마지막에 저장된 하나만** 쓸 수 있습니다.
                      요청 중에는 버튼을 막아 주세요.
                    """)
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "발급 성공"),
            @ApiResponse(responseCode = "401", description = "토큰이 없거나 유효하지 않은 경우",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = ApiProblemDetail.class))),
            @ApiResponse(responseCode = "403", description = "아동 토큰이 아닌 경우(점주·관리자 토큰 포함)",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = ApiProblemDetail.class)))
    })
    @PostMapping("/me/qr-token")
    public QrTokenResponse issueMine(@AuthenticationPrincipal AuthMember child) {
        return qrCredentialService.issue(AuthMember.requireChildId(child));
    }
}
