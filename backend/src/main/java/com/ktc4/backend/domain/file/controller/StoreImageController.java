package com.ktc4.backend.domain.file.controller;

import com.ktc4.backend.domain.file.FileIdFormat;
import com.ktc4.backend.domain.file.dto.StoreImageResponse;
import com.ktc4.backend.domain.file.service.UploadFileService;
import com.ktc4.backend.global.error.ApiProblemDetail;
import com.ktc4.backend.global.security.AuthMember;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.List;

/**
 * 가게 사진을 보는 진입점. 관리자와 그 가게에 연결된 점주만 본다.
 *
 * <p>로그인 없이 누구나 보던 것을 닫았다(멘토 10주차 리뷰 — 사진을 누구나 봐도 되는지는 기획이 정할 일).
 * 아동·보호자가 가게 사진을 보는 화면이 생기면 다시 연다 — {@code SecurityConfig} 의 규칙 한 줄과
 * {@link UploadFileService#loadImage} 의 권한 확인을 고치면 된다.
 *
 * <p>무엇을 내보낼지는 조회 쿼리({@code UploadFileRepository})가 정한다. 사업자등록증은 누구에게나 그 쿼리에 걸리지 않아
 * 여기서는 없는 파일이다(관리자는 {@code /api/admin/files/**} 로 연다). 점주에게는 승인 전 신청의 사진도 없는 파일이다.
 */
@Tag(name = "가게 사진", description = "가게의 대표 사진·메뉴 사진을 보는 API. 관리자와 그 가게에 연결된 점주만 부릅니다")
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class StoreImageController {

    // 보는 사람의 브라우저에만 잠깐 둔다. 중간 서버(공유 캐시)에는 남기지 않는다 — 로그인한 사람만 보는 사진이다.
    // 길게 두지 않는 이유: 연결을 끊으면 사진도 내려가야 하는데, 보관된 사본은 그 시간만큼 더 보인다.
    private static final CacheControl PRIVATE_CACHE = CacheControl.maxAge(Duration.ofMinutes(5)).cachePrivate();

    private final UploadFileService uploadFileService;

    @Operation(summary = "가게 사진 목록",
            description = """
                    가게의 대표 사진과 메뉴 사진 목록입니다. 점주가 가입을 신청할 때 올린 사진이고,
                    관리자가 가입을 승인해 그 가게에 연결한 뒤부터 보입니다. 올린 순서대로 옵니다.

                    관리자와 **그 가게에 연결된 점주**만 부를 수 있습니다. 점주에게는 없는 가게 번호여도 403 입니다
                    (가게가 있는지 알려 주지 않음, 체크인과 같음).

                    사진 내용은 `GET /api/files/{fileId}` 로 엽니다. 토큰이 필요해 `<img src>` 에 주소를 그대로 넣을 수 없습니다 —
                    `Authorization` 헤더를 붙여 받아 온 뒤 `URL.createObjectURL(blob)` 로 띄우세요.
                    메뉴마다 붙은 사진은 가게 상세(`GET /api/stores/{storeId}`)의 `menus[].photoFileId` 로도 알 수 있습니다.
                    """)
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "조회 성공 (사진이 없으면 빈 배열)"),
            @ApiResponse(responseCode = "401", description = "토큰이 없거나 유효하지 않은 경우",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = ApiProblemDetail.class))),
            @ApiResponse(responseCode = "403", description = "관리자도 아니고 이 가게에 연결된 점주도 아닌 경우",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = ApiProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "storeId 에 해당하는 가게가 없는 경우 (관리자만)",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = ApiProblemDetail.class)))
    })
    @GetMapping("/stores/{storeId}/images")
    public List<StoreImageResponse> getStoreImages(
            @Parameter(description = "가게 ID", example = "1") @PathVariable Long storeId,
            @AuthenticationPrincipal AuthMember authMember) {
        return uploadFileService.getStoreImages(storeId, authMember);
    }

    @Operation(summary = "가게 사진 열기",
            description = """
                    가게 사진의 내용을 내려줍니다. 응답 본문이 사진 그 자체입니다(JSON 이 아닙니다).
                    `Content-Type` 은 서버가 저장할 때 파일 내용으로 가린 형식입니다.

                    관리자는 모든 가게 사진을, 점주는 **자기와 같은 가게에 연결된 점주가 올린** 사진만 엽니다.
                    사업자등록증은 누구에게나 404 입니다(관리자는 관리자 첨부 API 로 엽니다).
                    점주에게는 승인 전이거나 거절된 신청의 사진, 연결이 끊긴 점주의 사진도 없는 파일과 똑같이 404 입니다.
                    """)
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "사진 내용",
                    content = {@Content(mediaType = "image/jpeg"), @Content(mediaType = "image/png")}),
            @ApiResponse(responseCode = "401", description = "토큰이 없거나 유효하지 않은 경우",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = ApiProblemDetail.class))),
            @ApiResponse(responseCode = "403", description = "점주·관리자가 아닌 경우",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = ApiProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "볼 수 있는 사진이 아닌 경우(file-not-found)",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = ApiProblemDetail.class)))
    })
    @GetMapping("/files/{fileId:" + FileIdFormat.PATH_REGEX + "}")
    public ResponseEntity<Resource> getImage(
            @Parameter(description = "파일 ID", example = "3f2a9c1e-5b7d-4e8a-9c0f-1a2b3c4d5e6f") @PathVariable String fileId,
            @AuthenticationPrincipal AuthMember authMember) {
        return FileResponses.of(uploadFileService.loadImage(fileId, authMember), PRIVATE_CACHE);
    }
}
