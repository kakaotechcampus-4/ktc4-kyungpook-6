package com.ktc4.backend.domain.file.controller;

import com.ktc4.backend.domain.file.dto.StoreImageResponse;
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
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.List;

/**
 * 가게 사진을 누구나 보는 진입점. 로그인하지 않아도 부를 수 있다({@code SecurityConfig} 에서 열어 둔다).
 *
 * <p>여기로 나가는 것은 가입이 승인돼 가게에 연결된 점주의 대표 사진·메뉴 사진뿐이다. 무엇을 내보낼지는
 * 이 컨트롤러가 아니라 조회 쿼리({@code UploadFileRepository})가 정한다 — 사업자등록증과 승인 전 신청의 사진은
 * 그 쿼리에 걸리지 않아 여기서는 없는 파일이다.
 */
@Tag(name = "가게 사진", description = "가게의 대표 사진·메뉴 사진을 보는 API. 로그인 없이 부를 수 있습니다")
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class FilePublicController {

    // 공개 사진이라 잠깐은 보관해도 된다. 길게 두지 않는 이유: 관리자가 점주와 가게의 연결을 끊으면 사진도 내려가야 하는데,
    // 보관된 사본은 그 시간만큼 더 보인다.
    private static final CacheControl PUBLIC_CACHE = CacheControl.maxAge(Duration.ofMinutes(5)).cachePublic();

    private final UploadFileService uploadFileService;

    @Operation(summary = "가게 사진 목록",
            description = """
                    가게의 대표 사진과 메뉴 사진 목록입니다. 점주가 가입을 신청할 때 올린 사진이고,
                    관리자가 가입을 승인해 그 가게에 연결한 뒤부터 보입니다. 올린 순서대로 옵니다(대표 사진 → 메뉴 사진).

                    사진 내용은 `GET /api/files/{fileId}` 로 엽니다. 그 주소는 토큰이 필요 없어 `<img src>` 에 그대로 넣을 수 있습니다.

                    - 연결된 점주가 없거나 사진을 올린 적이 없는 가게는 빈 배열입니다.
                    - 관리자가 점주와 가게의 연결을 끊으면 그 점주의 사진은 목록에서 빠집니다.
                    """)
    @SecurityRequirements
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "조회 성공 (사진이 없으면 빈 배열)"),
            @ApiResponse(responseCode = "400", description = "storeId 가 숫자가 아닌 경우",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = ApiProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "storeId 에 해당하는 가게가 없는 경우",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = ApiProblemDetail.class)))
    })
    @GetMapping("/stores/{storeId}/images")
    public List<StoreImageResponse> getStoreImages(
            @Parameter(description = "가게 ID", example = "1") @PathVariable Long storeId) {
        return uploadFileService.getStoreImages(storeId);
    }

    @Operation(summary = "가게 사진 열기",
            description = """
                    가게 사진의 내용을 내려줍니다. 응답 본문이 사진 그 자체입니다(JSON 이 아닙니다).
                    토큰이 필요 없습니다. `Content-Type` 은 서버가 저장할 때 파일 내용으로 가린 형식입니다.

                    열 수 있는 것은 승인된 점주가 올린 대표 사진·메뉴 사진뿐입니다. 사업자등록증, 승인 전이거나 거절된 신청의 사진,
                    연결이 끊긴 점주의 사진은 없는 파일과 똑같이 404 입니다.
                    """)
    @SecurityRequirements
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "사진 내용",
                    content = {@Content(mediaType = "image/jpeg"), @Content(mediaType = "image/png"),
                            @Content(mediaType = "image/webp")}),
            @ApiResponse(responseCode = "400", description = "fileId 가 숫자가 아닌 경우",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = ApiProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "공개된 사진이 아닌 경우(file-not-found)",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = ApiProblemDetail.class)))
    })
    @GetMapping("/files/{fileId}")
    public ResponseEntity<Resource> getImage(
            @Parameter(description = "파일 ID", example = "11") @PathVariable Long fileId) {
        return FileResponses.of(uploadFileService.loadPublic(fileId), PUBLIC_CACHE);
    }
}
