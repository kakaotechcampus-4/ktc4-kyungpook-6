package com.ktc4.backend.domain.file.controller;

import com.ktc4.backend.domain.file.dto.AttachmentResponse;
import com.ktc4.backend.domain.file.dto.StoredFile;
import com.ktc4.backend.domain.file.enums.FileType;
import com.ktc4.backend.domain.file.enums.UploadKind;
import com.ktc4.backend.domain.file.service.UploadFileService;
import com.ktc4.backend.domain.member.enums.MemberRole;
import com.ktc4.backend.global.error.CustomException;
import com.ktc4.backend.global.error.ErrorCode;
import com.ktc4.backend.global.security.JwtProvider;
import com.ktc4.backend.global.security.SecurityConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 첨부 파일 조회 API 확인. 실제 보안 규칙({@link SecurityConfig})으로 본다 — 사업자등록증이 관리자에게만 열리는지가 핵심이다.
 */
@WebMvcTest(FileAdminController.class)
@Import(SecurityConfig.class)
@DisplayName("가입 신청 첨부 파일 API")
class FileAdminControllerTest {

    private static final String LIST_URL = "/api/admin/owners/3/files";
    private static final String FILE_ID = "7b1d0e4f-2c3a-4d5b-8e6f-9a0b1c2d3e4f";
    private static final String FILE_URL = "/api/admin/files/" + FILE_ID;
    private static final byte[] CONTENT = "%PDF-1.7\n%test".getBytes(StandardCharsets.US_ASCII);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtProvider jwtProvider;

    @MockitoBean
    private UploadFileService uploadFileService;

    private String bearer(MemberRole role) {
        return "Bearer " + jwtProvider.issue(1L, role).value();
    }

    private void givenFile(UploadKind kind, FileType type) {
        when(uploadFileService.load(FILE_ID))
                .thenReturn(new StoredFile(FILE_ID, kind, type, CONTENT.length, new ByteArrayResource(CONTENT)));
    }

    @Nested
    @DisplayName("관리자만 볼 수 있다")
    class AdminOnly {

        @Test
        @DisplayName("토큰이 없으면 목록도 파일도 401 이고 서비스에 닿지 않는다")
        void rejectsAnonymous() throws Exception {
            mockMvc.perform(get(LIST_URL)).andExpect(status().isUnauthorized());
            mockMvc.perform(get(FILE_URL)).andExpect(status().isUnauthorized());

            verifyNoInteractions(uploadFileService);
        }

        @Test
        @DisplayName("점주 토큰으로는 403 — 다른 점주의 사업자등록증을 볼 수 없다")
        void rejectsOwner() throws Exception {
            mockMvc.perform(get(LIST_URL).header(HttpHeaders.AUTHORIZATION, bearer(MemberRole.OWNER)))
                    .andExpect(status().isForbidden());
            mockMvc.perform(get(FILE_URL).header(HttpHeaders.AUTHORIZATION, bearer(MemberRole.OWNER)))
                    .andExpect(status().isForbidden());

            verifyNoInteractions(uploadFileService);
        }

        @Test
        @DisplayName("가입 상태 확인용 토큰으로도 403 — 신청자 본인이 올린 파일이어도 이 API 로는 열지 못한다")
        void rejectsSignupStatusToken() throws Exception {
            String applicant = "Bearer " + jwtProvider.issueSignupStatus(3L).value();

            mockMvc.perform(get(LIST_URL).header(HttpHeaders.AUTHORIZATION, applicant))
                    .andExpect(status().isForbidden());
            mockMvc.perform(get(FILE_URL).header(HttpHeaders.AUTHORIZATION, applicant))
                    .andExpect(status().isForbidden());

            verifyNoInteractions(uploadFileService);
        }
    }

    @Nested
    @DisplayName("첨부 목록")
    class Attachments {

        @Test
        @DisplayName("관리자에게 파일 ID·종류·형식·크기를 준다 — 저장한 이름이나 경로는 담지 않는다")
        void listsAttachments() throws Exception {
            when(uploadFileService.getAttachments(3L)).thenReturn(List.of(
                    new AttachmentResponse("3f2a9c1e-5b7d-4e8a-9c0f-1a2b3c4d5e6f", UploadKind.STORE_IMAGE, FileType.JPEG, 100L,
                            LocalDateTime.of(2026, 10, 14, 9, 0)),
                    new AttachmentResponse(FILE_ID, UploadKind.BIZ_CERT, FileType.PDF, 200L,
                            LocalDateTime.of(2026, 10, 14, 9, 0))));

            mockMvc.perform(get(LIST_URL).header(HttpHeaders.AUTHORIZATION, bearer(MemberRole.ADMIN)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(2))
                    .andExpect(jsonPath("$[0].fileId").value("3f2a9c1e-5b7d-4e8a-9c0f-1a2b3c4d5e6f"))
                    .andExpect(jsonPath("$[0].kind").value("STORE_IMAGE"))
                    .andExpect(jsonPath("$[0].fileType").value("JPEG"))
                    .andExpect(jsonPath("$[0].sizeBytes").value(100))
                    .andExpect(jsonPath("$[1].kind").value("BIZ_CERT"))
                    .andExpect(jsonPath("$[0].storedName").doesNotExist())
                    .andExpect(jsonPath("$[0].path").doesNotExist());
        }

        @Test
        @DisplayName("없는 점주면 404 member-not-found")
        void unknownMember() throws Exception {
            when(uploadFileService.getAttachments(3L)).thenThrow(new CustomException(ErrorCode.MEMBER_NOT_FOUND));

            mockMvc.perform(get(LIST_URL).header(HttpHeaders.AUTHORIZATION, bearer(MemberRole.ADMIN)))
                    .andExpect(status().isNotFound())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.type").value(ErrorCode.MEMBER_NOT_FOUND.getType().toString()));
        }

        @Test
        @DisplayName("회원 ID 가 숫자가 아니면 400")
        void nonNumericMemberId() throws Exception {
            mockMvc.perform(get("/api/admin/owners/abc/files")
                            .header(HttpHeaders.AUTHORIZATION, bearer(MemberRole.ADMIN)))
                    .andExpect(status().isBadRequest());
        }
    }

    @Nested
    @DisplayName("파일 열기")
    class Download {

        @Test
        @DisplayName("사업자등록증은 내려받기로 준다 — 형식은 서버가 가린 값, 파일 이름은 서버가 지은 값이다")
        void bizCertIsAttachment() throws Exception {
            givenFile(UploadKind.BIZ_CERT, FileType.PDF);

            mockMvc.perform(get(FILE_URL).header(HttpHeaders.AUTHORIZATION, bearer(MemberRole.ADMIN)))
                    .andExpect(status().isOk())
                    .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                    .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename=\"biz-cert-" + FILE_ID + ".pdf\""))
                    .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                    .andExpect(header().string("Content-Security-Policy", "default-src 'none'; sandbox"))
                    .andExpect(header().longValue(HttpHeaders.CONTENT_LENGTH, CONTENT.length))
                    .andExpect(content().bytes(CONTENT));
        }

        @Test
        @DisplayName("사진으로 낸 사업자등록증도 내려받기로 준다 — 형식이 아니라 종류로 정한다")
        void bizCertPhotoIsAttachment() throws Exception {
            givenFile(UploadKind.BIZ_CERT, FileType.JPEG);

            mockMvc.perform(get(FILE_URL).header(HttpHeaders.AUTHORIZATION, bearer(MemberRole.ADMIN)))
                    .andExpect(status().isOk())
                    .andExpect(content().contentType(MediaType.IMAGE_JPEG))
                    .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename=\"biz-cert-" + FILE_ID + ".jpg\""));
        }

        @Test
        @DisplayName("파일 ID 모양이 아니면 서비스에 닿지 않고 404 — 예전 숫자 번호, 대문자 UUID")
        void rejectsMalformedFileId() throws Exception {
            for (String id : List.of("12", FILE_ID.toUpperCase(Locale.ROOT), FILE_ID + "0")) {
                mockMvc.perform(get("/api/admin/files/" + id).header(HttpHeaders.AUTHORIZATION, bearer(MemberRole.ADMIN)))
                        .andExpect(status().isNotFound());
            }

            verifyNoInteractions(uploadFileService);
        }

        @Test
        @DisplayName("사진은 화면에 바로 띄울 수 있게 준다")
        void imageIsInline() throws Exception {
            givenFile(UploadKind.STORE_IMAGE, FileType.JPEG);
            mockMvc.perform(get(FILE_URL).header(HttpHeaders.AUTHORIZATION, bearer(MemberRole.ADMIN)))
                    .andExpect(status().isOk())
                    .andExpect(content().contentType(MediaType.IMAGE_JPEG))
                    .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                            "inline; filename=\"store-image-" + FILE_ID + ".jpg\""))
                    .andExpect(header().string("X-Content-Type-Options", "nosniff"));

            givenFile(UploadKind.MENU_IMAGE, FileType.PNG);
            mockMvc.perform(get(FILE_URL).header(HttpHeaders.AUTHORIZATION, bearer(MemberRole.ADMIN)))
                    .andExpect(content().contentType(MediaType.IMAGE_PNG))
                    .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                            "inline; filename=\"menu-image-" + FILE_ID + ".png\""));
        }

        @Test
        @DisplayName("파일 응답은 브라우저나 중간 서버가 보관하지 않게 내려준다")
        void notCached() throws Exception {
            givenFile(UploadKind.BIZ_CERT, FileType.PDF);

            mockMvc.perform(get(FILE_URL).header(HttpHeaders.AUTHORIZATION, bearer(MemberRole.ADMIN)))
                    .andExpect(header().string(HttpHeaders.CACHE_CONTROL,
                            org.hamcrest.Matchers.containsString("no-store")));
        }

        @Test
        @DisplayName("없는 파일이면 404 file-not-found — 에러 응답은 가이드의 모양이다")
        void missingFile() throws Exception {
            when(uploadFileService.load(FILE_ID)).thenThrow(new CustomException(ErrorCode.FILE_NOT_FOUND));

            mockMvc.perform(get(FILE_URL).header(HttpHeaders.AUTHORIZATION, bearer(MemberRole.ADMIN)))
                    .andExpect(status().isNotFound())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.type").value(ErrorCode.FILE_NOT_FOUND.getType().toString()))
                    .andExpect(jsonPath("$.title").value(ErrorCode.FILE_NOT_FOUND.getTitle()))
                    .andExpect(jsonPath("$.detail").doesNotExist());
        }
    }
}
