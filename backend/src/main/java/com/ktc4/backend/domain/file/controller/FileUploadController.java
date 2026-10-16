package com.ktc4.backend.domain.file.controller;

import com.ktc4.backend.domain.file.dto.UploadResponse;
import com.ktc4.backend.domain.file.enums.UploadKind;
import com.ktc4.backend.domain.file.service.UploadFileService;
import com.ktc4.backend.global.error.ApiProblemDetail;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 가입 신청에 붙일 파일을 올리는 진입점. 로그인 없이 부른다 — 가입 신청 전이라 계정이 없다.
 *
 * <p>로그인 없이 열린 API 라 같은 곳에서 되풀이하면 막고({@code UploadRateLimitFilter}), 아무 신청도 가져가지 않은
 * 파일은 세 시간 뒤 지운다.
 */
@Tag(name = "파일 업로드", description = "점주 가입 신청에 붙일 사진·사업자등록증을 올리는 API")
@RestController
@RequestMapping("/api/files")
@RequiredArgsConstructor
public class FileUploadController {

    private final UploadFileService uploadFileService;

    @Operation(summary = "파일 올리기",
            description = """
                    가입 신청에 붙일 파일을 하나 올리고 파일 ID 를 받습니다. 토큰이 필요 없습니다.
                    가입 화면에서 사진을 고를 때마다 부르고, 받은 `fileId` 를 가입 신청(`POST /api/auth/owners/signup`)에 싣습니다.

                    요청은 `multipart/form-data` 이고 칸은 두 가지입니다.

                    | 칸 | 내용 |
                    |---|---|
                    | `kind` | `STORE_IMAGE`(가게 대표 사진) · `MENU_IMAGE`(메뉴 사진) · `BIZ_CERT`(사업자등록증) |
                    | `file` | 파일 하나 |

                    | 종류 | 받는 형식 | 크기 |
                    |---|---|---|
                    | `STORE_IMAGE`, `MENU_IMAGE` | JPEG·PNG | 5MB |
                    | `BIZ_CERT` | PDF | 10MB |
                    | `BIZ_CERT` | JPEG·PNG (휴대폰으로 찍은 사진) | 5MB |

                    - 파일 형식은 파일 이름이 아니라 실제 내용으로 확인합니다. WebP·HEIC(아이폰 기본 형식)는 받지 않습니다.
                    - 사진은 저장할 때 **찍은 위치(GPS)·기기·시각 같은 부가 정보를 지웁니다.** 사진의 방향만 남깁니다.
                      화질은 그대로입니다. 구조가 깨진 사진은 `unsupported-file-type` 입니다.
                    - 보낸 파일 이름은 저장하지 않습니다.
                    - **올리고 세 시간 안에 가입 신청에 싣지 않으면 지워집니다.** 그 뒤 가입하면 `invalid-file-reference` 이니
                      다시 올려 주세요. 한 파일 ID 는 한 번만 쓸 수 있습니다.

                    같은 곳(IPv4 는 주소 하나, IPv6 는 /64 대역)에서 10분에 60번 넘게 올리거나(성공·실패를 가리지 않고 셉니다),
                    하루에 150개 넘게 올리면 429 `too-many-requests` 입니다.
                    """)
    @SecurityRequirements
    @ApiResponses(value = {
            @ApiResponse(responseCode = "201", description = "올림 — 파일 ID 를 돌려줍니다"),
            @ApiResponse(responseCode = "400",
                    description = "kind·file 이 빠졌거나 kind 값이 틀린 경우(invalid-request), 빈 파일(empty-file), "
                            + "받지 않는 형식이거나 구조가 깨진 파일(unsupported-file-type)",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = ApiProblemDetail.class))),
            @ApiResponse(responseCode = "413", description = "종류·형식별 크기를 넘은 경우(file-too-large)",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = ApiProblemDetail.class))),
            @ApiResponse(responseCode = "429", description = "같은 곳에서 너무 자주 올린 경우(too-many-requests). Retry-After 헤더 참고",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = ApiProblemDetail.class))),
            @ApiResponse(responseCode = "503", description = "가입 신청에 쓰이지 않은 파일이 너무 많이 쌓인 경우(signup-temporarily-closed)",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = ApiProblemDetail.class)))
    })
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public UploadResponse upload(
            @Parameter(description = "파일 종류", example = "STORE_IMAGE") @RequestParam("kind") UploadKind kind,
            @Parameter(description = "올릴 파일") @RequestPart("file") MultipartFile file) {
        return uploadFileService.upload(kind, file);
    }
}
