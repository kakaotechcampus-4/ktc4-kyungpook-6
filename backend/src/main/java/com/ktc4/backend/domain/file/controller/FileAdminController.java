package com.ktc4.backend.domain.file.controller;

import com.ktc4.backend.domain.file.dto.AttachmentResponse;
import com.ktc4.backend.domain.file.dto.StoredFile;
import com.ktc4.backend.domain.file.enums.UploadKind;
import com.ktc4.backend.domain.file.service.UploadFileService;
import com.ktc4.backend.global.error.ApiProblemDetail;
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
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Locale;

/**
 * 관리자가 가입 신청에 첨부된 파일을 보는 진입점.
 *
 * <p>경로를 {@code /api/admin/**} 아래에 둔다 — {@code SecurityConfig} 에서 이 경로는 관리자만 부른다.
 * 사업자등록증에는 대표자의 이름·주소 같은 개인정보가 있어 언제나 관리자만 본다. 가게 사진도 승인 전에는 관리자만 본다.
 */
@Tag(name = "가입 신청 첨부 파일(관리자)", description = "관리자가 점주 가입 신청에 첨부된 사진·사업자등록증을 확인하는 API")
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class FileAdminController {

    private static final String NO_SNIFF_HEADER = "X-Content-Type-Options";

    private final UploadFileService uploadFileService;

    @Operation(summary = "가입 신청 첨부 파일 목록",
            description = """
                    가입 신청한 점주가 올린 파일의 목록입니다. 올린 순서대로 옵니다(가게 대표 사진 → 메뉴 사진 → 사업자등록증).
                    파일 내용은 `GET /api/admin/files/{fileId}` 로 엽니다.

                    파일 업로드가 생기기 전에 가입한 점주는 빈 배열입니다.
                    """)
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "조회 성공 (첨부가 없으면 빈 배열)"),
            @ApiResponse(responseCode = "404", description = "해당 ID 의 점주가 없는 경우",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = ApiProblemDetail.class)))
    })
    @GetMapping("/owners/{memberId}/files")
    public List<AttachmentResponse> getAttachments(
            @Parameter(description = "가입 신청한 회원 ID", example = "3") @PathVariable Long memberId) {
        return uploadFileService.getAttachments(memberId);
    }

    @Operation(summary = "첨부 파일 열기",
            description = """
                    첨부 파일의 내용을 내려줍니다. 응답 본문이 파일 그 자체입니다(JSON 이 아닙니다).

                    - 사진(가게 대표 사진·메뉴 사진)은 화면에 바로 띄울 수 있게 내려줍니다.
                    - 사업자등록증(PDF)은 내려받기로 내려줍니다 — 브라우저 탭에서 바로 열리지 않습니다.
                    - `Content-Type` 은 서버가 저장할 때 파일 내용으로 가린 형식입니다.

                    ⚠️ 토큰이 필요한 API 라 `<img src>` 에 주소를 그대로 넣으면 401 입니다.
                    `Authorization` 헤더를 붙여 받아 온 뒤 `URL.createObjectURL(blob)` 로 띄우세요.

                    ⚠️ 그렇게 받으면 "내려받기로 준다"는 헤더는 브라우저가 따르지 않습니다 — 어떻게 열지는 화면 코드가 정합니다.
                    사업자등록증은 `window.open(blobUrl)` 로 새 탭에 띄우지 말고 `<a href={blobUrl} download>` 로 내려받게 해 주세요.
                    새 탭에 띄우면 PDF 가 우리 사이트 주소에서 열립니다. 사진은 `<img src={blobUrl}>` 로 띄워도 됩니다.
                    """)
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "파일 내용",
                    content = {@Content(mediaType = "image/jpeg"), @Content(mediaType = "image/png"),
                            @Content(mediaType = "image/webp"), @Content(mediaType = "application/pdf")}),
            @ApiResponse(responseCode = "404", description = "해당 ID 의 파일이 없는 경우(file-not-found)",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = ApiProblemDetail.class)))
    })
    @GetMapping("/files/{fileId}")
    public ResponseEntity<Resource> getFile(
            @Parameter(description = "파일 ID", example = "12") @PathVariable Long fileId) {
        StoredFile file = uploadFileService.load(fileId);
        return ResponseEntity.ok()
                // 형식은 저장할 때 서버가 가린 값으로 고정한다. 브라우저가 내용을 보고 다른 형식으로 해석하지도 못하게 한다 —
                // 앞부분만 사진이고 뒤에 스크립트가 붙은 파일이 HTML 로 열리는 것을 막는다.
                .contentType(MediaType.parseMediaType(file.fileType().mediaType()))
                .header(NO_SNIFF_HEADER, "nosniff")
                // 사업자등록증이 브라우저나 중간 서버에 남지 않게 한다. 보안 필터가 기본으로 붙이는 값이지만
                // 그 설정이 바뀌어도 빠지지 않도록 여기서 직접 붙인다.
                .cacheControl(CacheControl.noStore())
                .contentLength(file.sizeBytes())
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition(file).toString())
                .body(file.content());
    }

    // 사업자등록증은 내려받게 한다. PDF 는 안에 스크립트를 담을 수 있어, 우리 주소에서 브라우저가 바로 열게 두지 않는다.
    // 파일 이름은 서버가 짓는다 — 보낸 쪽이 붙인 이름은 저장하지도 않았다.
    private static ContentDisposition disposition(StoredFile file) {
        String filename = file.kind().name().toLowerCase(Locale.ROOT).replace('_', '-') + "-" + file.fileId()
                + "." + file.fileType().extension();
        ContentDisposition.Builder builder = file.kind() == UploadKind.BIZ_CERT
                ? ContentDisposition.attachment()
                : ContentDisposition.inline();
        return builder.filename(filename).build();
    }
}
