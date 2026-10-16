package com.ktc4.backend.domain.auth.controller;

import com.ktc4.backend.domain.auth.dto.LoginRequest;
import com.ktc4.backend.domain.auth.dto.LoginResponse;
import com.ktc4.backend.domain.auth.dto.MemberResponse;
import com.ktc4.backend.domain.auth.dto.OwnerSignupRequest;
import com.ktc4.backend.domain.auth.dto.OwnerSignupResponse;
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

                    응답의 `statusToken` 은 가입 상태 확인용 토큰입니다. `Authorization: Bearer <statusToken>` 으로
                    `GET /api/auth/me` 를 부르면 `status`(PENDING·APPROVED·REJECTED)를 볼 수 있습니다.
                    이 토큰으로는 그 API 만 부를 수 있고, 승인된 뒤에는 로그인해서 접근 토큰을 받아야 합니다.
                    비밀번호를 앱에 들고 있지 말고 이 토큰을 보관하세요.

                    사업자등록번호는 하이픈이 있어도 되고, 서버가 숫자 10자리로 맞춰 저장합니다.
                    휴대폰 번호(`phone`)도 하이픈이 있어도 되며 010·011·016·017·018·019 로 시작해야 합니다.
                    가게를 등록할 때 적은 번호와 같으면 관리자가 가게를 찾기 쉬워집니다.

                    가게 정보도 함께 받습니다 — 가게 주소(`storeAddress`)·가게 전화번호(`storePhone`, 매장 유선 번호도 됨)·
                    영업시간(`businessHours`, 1~21줄)·메뉴(`menus`, 1~50개). 모두 필수이고 신청서에 저장됩니다.
                    관리자가 승인하면 그 가게의 **비어 있는 항목에만** 옮겨 적습니다(이미 값이 있는 항목은 바꾸지 않음).
                    영업시간은 `HH:mm` 이고 자정에 닫으면 `24:00`, 여는 시간보다 이르면 다음 날 닫는다는 뜻입니다.
                    형식 오류는 `errors` 에 몇 번째 줄의 어느 칸인지(예: `businessHours[0].close`) 담깁니다.
                    영업시간끼리 겹치면(같은 요일, 또는 자정을 넘긴 시간과 다음 날 시간) `errors` 에 `businessHours` 칸으로 담깁니다.

                    요청 본문은 JSON(`application/json`)입니다. 사진·사업자등록증은 이 요청에 싣지 않습니다.
                    파일 업로드 API 로 먼저 올리고, 받은 파일 ID 를 가입 요청에 넘기는 방식으로 바뀔 예정입니다(다음 배포).
                    그 전까지 가입 신청에는 첨부가 없습니다.

                    같은 곳(IPv4 는 주소 하나, IPv6 는 /64 대역)에서 10분에 10번 넘게 보내거나(성공·실패를 가리지 않고 셉니다)
                    하루에 5건 넘게 가입에 성공하면 429 `too-many-requests` 입니다. 본문이 너무 커서(64KB 초과) 거절된 요청도 셉니다.
                    `Retry-After` 헤더의 초만큼 기다린 뒤 다시 보내세요.
                    """)
    @SecurityRequirements
    @ApiResponses(value = {
            @ApiResponse(responseCode = "201", description = "신청 완료 — 상태는 PENDING(승인 대기). 가입 상태 확인용 토큰을 함께 줍니다"),
            @ApiResponse(responseCode = "400",
                    description = "입력값이 비었거나 형식이 틀리거나 영업시간이 겹치는 경우(errors 에 필드 표시), "
                            + "사업자등록번호가 10자리가 아닌 경우(invalid-biz-no), 휴대폰 번호 형식이 아닌 경우(invalid-phone)",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = ApiProblemDetail.class))),
            @ApiResponse(responseCode = "409", description = "이미 가입된 이메일",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = ApiProblemDetail.class))),
            @ApiResponse(responseCode = "413", description = "본문(JSON)이 64KB 를 넘는 경우(request-too-large). 본문을 읽지 않고 거절합니다",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = ApiProblemDetail.class))),
            @ApiResponse(responseCode = "415", description = "본문이 JSON(application/json)이 아닌 경우",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = ApiProblemDetail.class))),
            @ApiResponse(responseCode = "429", description = "같은 IP 에서 너무 자주 보낸 경우(too-many-requests). Retry-After 헤더 참고",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = ApiProblemDetail.class))),
            @ApiResponse(responseCode = "503", description = "검토되지 않은 신청이 너무 많이 쌓여 가입을 잠시 받지 않는 경우(signup-temporarily-closed)",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = ApiProblemDetail.class)))
    })
    @PostMapping("/owners/signup")
    @ResponseStatus(HttpStatus.CREATED)
    public OwnerSignupResponse signupOwner(@Valid @RequestBody OwnerSignupRequest request) {
        return authService.signupOwner(request);
    }

    @Operation(summary = "내 정보 조회", description = """
            토큰의 주인이 누구인지 확인합니다. 앱을 다시 열었을 때 토큰이 아직 유효한지 확인하는 데도 씁니다.

            점주는 `stores` 에 연결된 가게가 옵니다(처음 연결한 순서 — 다시 이어도 그대로, 끊긴 연결 제외). 체크인·가게 관리 API 의 `storeId` 로 쓰세요.
            가게를 고르는 화면이 없으면 첫 번째를 씁니다. 관리자와 승인 전 신청자는 빈 목록입니다.

            가입 신청 때 받은 가입 상태 확인용 토큰(`statusToken`)으로도 부를 수 있습니다. 승인 전 신청자는
            `status` 로 승인 여부를 확인합니다 — APPROVED 가 되면 로그인 화면으로 보내 주세요.
            """)
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
