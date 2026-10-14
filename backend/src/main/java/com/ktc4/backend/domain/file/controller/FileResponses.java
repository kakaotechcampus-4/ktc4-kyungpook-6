package com.ktc4.backend.domain.file.controller;

import com.ktc4.backend.domain.file.dto.StoredFile;
import com.ktc4.backend.domain.file.enums.UploadKind;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.Locale;

/**
 * 파일 내용을 내려주는 응답을 만든다. 관리자용·공개용 진입점이 같은 규칙으로 내려주게 한곳에 둔다 —
 * 한쪽에서만 헤더가 빠지는 일이 없게.
 */
final class FileResponses {

    private static final String NO_SNIFF_HEADER = "X-Content-Type-Options";
    private static final String CSP_HEADER = "Content-Security-Policy";

    private FileResponses() {
    }

    /**
     * @param file  내려줄 파일
     * @param cache 응답을 브라우저·중간 서버가 보관해도 되는지
     */
    static ResponseEntity<Resource> of(StoredFile file, CacheControl cache) {
        return ResponseEntity.ok()
                // 형식은 저장할 때 서버가 가린 값으로 고정한다. 브라우저가 내용을 보고 다른 형식으로 해석하지도 못하게 한다 —
                // 앞부분만 사진이고 뒤에 스크립트가 붙은 파일이 HTML 로 열리는 것을 막는다.
                .contentType(MediaType.parseMediaType(file.fileType().mediaType()))
                .header(NO_SNIFF_HEADER, "nosniff")
                // 한 겹 더 — 이 응답이 문서로 열리더라도 그 안에서 스크립트가 돌거나 다른 것을 불러오지 못하게 한다.
                // <img> 로 띄우는 데는 영향이 없다(이 헤더는 응답을 문서로 열 때만 적용된다).
                .header(CSP_HEADER, "default-src 'none'; sandbox")
                // 보안 필터가 기본으로 붙이는 값에 기대지 않고 진입점마다 직접 정한다
                .cacheControl(cache)
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
