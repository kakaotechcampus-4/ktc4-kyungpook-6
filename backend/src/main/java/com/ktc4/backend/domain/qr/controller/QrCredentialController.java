package com.ktc4.backend.domain.qr.controller;

import com.ktc4.backend.domain.qr.dto.QrTokenResponse;
import com.ktc4.backend.domain.qr.service.QrCredentialService;
import com.ktc4.backend.global.error.ApiProblemDetail;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
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
            summary = "아동 QR 발급",
            description = """
                    아동의 QR 에 담을 문자열(`v1.<토큰>`)을 발급합니다. 앱은 이 값을 기기에 저장해 두고
                    QR 이미지로 그려서 보여줍니다. 보여줄 때는 인터넷이 필요 없습니다.

                    - 이 값은 **이 응답에서 한 번만** 나갑니다. 서버는 해시만 보관해서 다시 알려줄 수 없습니다.
                    - 이미 발급받은 아동이 다시 부르면 **재발급**이고, 옛 QR 은 바로 쓸 수 없게 됩니다.
                      QR 화면이 유출됐을 때 이렇게 막습니다.

                    ⚠️ 개발 단계라 아직 누구나 아무 `childId` 로 부를 수 있습니다. 카드 등록(아동 인증)이 생기면
                    본인만 부를 수 있게 바뀝니다.
                    """)
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "발급 성공"),
            @ApiResponse(responseCode = "400", description = "childId 가 양의 정수가 아닌 경우",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = ApiProblemDetail.class)))
    })
    @PostMapping("/{childId}/qr-token")
    public QrTokenResponse issue(
            @Parameter(description = "아동 번호", example = "7")
            @PathVariable @Positive Long childId) {

        return qrCredentialService.issue(childId);
    }
}
