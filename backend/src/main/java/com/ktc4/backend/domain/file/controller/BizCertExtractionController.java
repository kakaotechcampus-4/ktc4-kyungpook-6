package com.ktc4.backend.domain.file.controller;

import com.ktc4.backend.domain.file.FileIdFormat;
import com.ktc4.backend.domain.file.dto.BizCertExtractionResponse;
import com.ktc4.backend.global.error.ApiProblemDetail;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 올린 사업자등록증을 읽어 가입 form 에 채울 값을 돌려주는 진입점. 로그인 없이 부른다 — 가입 신청 전이라 계정이 없다.
 *
 * <p>업로드({@link FileUploadController})와 나눈 이유: 읽기는 모델을 불러 몇 초 걸리고 실패할 수 있다. 업로드에 붙이면
 * 읽기가 실패할 때 업로드까지 실패하고, 사진을 올릴 때마다 기다리게 된다.
 */
@Tag(name = "파일 업로드", description = "점주 가입 신청에 붙일 사진·사업자등록증을 올리는 API")
@RestController
@RequestMapping("/api/files")
public class BizCertExtractionController {

    @Operation(summary = "사업자등록증 읽기 (가입 form 채우기)",
            description = """
                    가입 화면에서 올린 사업자등록증(`kind=BIZ_CERT`)을 읽어 가입 form 에 채울 값을 돌려줍니다. 토큰이 필요 없고
                    요청 본문도 없습니다. 사업자등록증을 올려 `fileId` 를 받은 직후에 부르면 됩니다.

                    - 응답 칸 이름은 가입 신청(`POST /api/auth/owners/signup`)의 칸 이름과 같습니다. **이미 채운 칸은 덮어쓰지
                      말아 주세요** — 점주가 직접 고친 값이 사라집니다.
                    - 못 읽은 칸은 `null` 입니다. 사진이 흐리면 일부만 읽힐 수 있습니다.
                    - 몇 초 걸릴 수 있습니다. 어떤 이유로든 실패하면 직접 입력으로 넘어가면 됩니다.
                    - 읽은 값은 서버에 남기지 않습니다. 가입 신청에 실어야 남습니다.
                    - 가입 신청에 아직 쓰지 않은 사업자등록증만 읽습니다. 올린 지 세 시간이 지나 지워졌거나, 이미 가입 신청에
                      쓴 파일, 사진 파일 ID 는 `invalid-file-reference` 입니다.
                    - 부를 때마다 모델 비용이 들어 횟수를 막습니다. 같은 곳(IPv4 는 주소 하나, IPv6 는 /64 대역)에서 10분에 5번·하루에
                      10번, 같은 파일 ID 로 3번을 넘으면 429 `too-many-requests` 입니다. 성공·실패를 가리지 않고 셉니다.

                    ⚠️ **아직 구현 전이라 본문 없이 501 을 돌려줍니다.** 응답 모양(200)은 확정이라, 이 모양으로 화면을 먼저 만들어 주세요.
                    """)
    @SecurityRequirements
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "읽음 — 못 읽은 칸은 null (구현 후)"),
            @ApiResponse(responseCode = "400",
                    description = "없는 파일 ID·세 시간이 지나 지워진 파일·이미 가입 신청에 쓴 파일·사업자등록증이 아닌 파일"
                            + "(invalid-file-reference). 어느 경우인지 나누어 알려 주지 않습니다 (구현 후)",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = ApiProblemDetail.class))),
            @ApiResponse(responseCode = "429", description = "같은 곳이나 같은 파일 ID 로 너무 자주 부른 경우(too-many-requests). "
                    + "Retry-After 헤더 참고",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = ApiProblemDetail.class))),
            @ApiResponse(responseCode = "503", description = "지금 사업자등록증을 읽을 수 없는 경우 — 직접 입력으로 넘어가 주세요 (구현 후)",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = ApiProblemDetail.class))),
            @ApiResponse(responseCode = "501", description = "아직 구현 전 — 본문 없음", content = @Content)
    })
    @PostMapping("/{fileId:" + FileIdFormat.PATH_REGEX + "}/biz-cert-extraction")
    public ResponseEntity<BizCertExtractionResponse> extract(
            @Parameter(description = "사업자등록증을 올리고 받은 파일 ID", example = "3f2a9c1e-5b7d-4e8a-9c0f-1a2b3c4d5e6f")
            @PathVariable String fileId) {

        // 인터페이스만 먼저 공개한다. 가짜 본문을 주면 읽은 값처럼 보여 실패가 가려지므로 본문을 비운다.
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
    }
}
