package com.ktc4.backend.domain.checkin.controller;

import com.ktc4.backend.domain.checkin.dto.CheckInRequest;
import com.ktc4.backend.domain.checkin.dto.CheckInResponse;
import com.ktc4.backend.domain.checkin.service.CheckInService;
import com.ktc4.backend.global.error.ApiProblemDetail;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "체크인", description = "점주가 아동 QR 을 찍어 방문을 확인하는 API")
@RestController
@RequiredArgsConstructor
public class CheckInController {

    private final CheckInService checkInService;

    @Operation(
            summary = "QR 체크인",
            description = """
                    점주 앱이 아동의 QR 을 스캔해 읽은 문자열을 보내면, 서버가 등록된 아동인지 확인하고 체크인을 기록합니다.

                    - 응답에는 **아동을 알아볼 수 있는 정보가 없습니다.** 확인됐다는 결과(`checkInId`, `checkedInAt`)만 옵니다.
                    - QR 이 맞지 않으면 이유와 관계없이 `invalid-qr-token` 하나로 옵니다
                      (발급한 적 없음, 재발급으로 바뀐 옛 QR, 형식 오류). 화면에는 "유효하지 않은 QR" 로 보여주면 됩니다.
                    - 가게가 없으면 QR 을 보기 전에 `store-not-found` 가 옵니다.

                    ⚠️ 권한 검사 스위치(`AUTH_ENFORCE`, 기본 설정은 꺼짐)가 켜지면 점주·관리자 토큰이 필요합니다.
                    점주 로그인은 생겼지만 점주 ↔ 가게 연결이 아직 없어서, 로그인한 점주라면 아무 가게 번호로나
                    기록할 수 있습니다. 연결이 생기면 자기 가게만 가능해집니다.
                    """)
    @ApiResponses(value = {
            @ApiResponse(responseCode = "201", description = "체크인 기록 성공"),
            @ApiResponse(responseCode = "400",
                    description = "QR 이 유효하지 않거나(invalid-qr-token), qrPayload 가 비었거나 100자를 넘거나 "
                            + "storeId 가 숫자가 아닌 경우(invalid-request)",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = ApiProblemDetail.class))),
            @ApiResponse(responseCode = "401", description = "권한 검사가 켜졌을 때, 토큰이 없거나 유효하지 않은 경우",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = ApiProblemDetail.class))),
            @ApiResponse(responseCode = "403", description = "권한 검사가 켜졌을 때, 점주·관리자가 아닌 경우",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = ApiProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "storeId 에 해당하는 가게가 없는 경우(0·음수 포함)",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = ApiProblemDetail.class)))
    })
    @PostMapping("/api/stores/{storeId}/check-ins")
    @ResponseStatus(HttpStatus.CREATED)
    public CheckInResponse checkIn(
            // @Positive 를 달지 않는다. 경로 변수에 제약을 달면 Spring 이 메서드 단위 검증으로 바꿔서
            // 본문 검증 실패 응답에서 필드별 errors 가 사라진다. 0·음수는 없는 가게라 404 로 떨어진다.
            @Parameter(description = "체크인할 가게 ID", example = "1")
            @PathVariable Long storeId,
            @Valid @RequestBody CheckInRequest request) {

        return checkInService.checkIn(storeId, request.qrPayload());
    }
}
