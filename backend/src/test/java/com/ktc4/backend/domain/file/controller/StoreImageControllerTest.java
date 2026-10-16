package com.ktc4.backend.domain.file.controller;

import com.ktc4.backend.domain.file.dto.StoreImageResponse;
import com.ktc4.backend.domain.file.dto.StoredFile;
import com.ktc4.backend.domain.file.enums.FileType;
import com.ktc4.backend.domain.file.enums.UploadKind;
import com.ktc4.backend.domain.file.service.UploadFileService;
import com.ktc4.backend.domain.member.enums.MemberRole;
import com.ktc4.backend.global.error.CustomException;
import com.ktc4.backend.global.error.ErrorCode;
import com.ktc4.backend.global.security.AuthMember;
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

import java.util.List;
import java.util.Locale;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 가게 사진 API 확인. 실제 보안 규칙({@link SecurityConfig})으로 본다 — 로그인한 점주·관리자만 닿는지,
 * 그리고 관리자 전용 조회(사업자등록증까지 꺼내는 길)와 섞이지 않는지.
 *
 * <p>"그 가게에 연결된 점주인지"는 서비스와 조회 쿼리가 본다({@code UploadFileServiceTest}, {@code UploadFileRepositoryTest}).
 * 관리자용 진입점도 함께 띄운다. 두 경로가 서로 다른 서비스 메서드를 부르는지 보려는 것이다.
 */
@WebMvcTest({StoreImageController.class, FileAdminController.class})
@Import(SecurityConfig.class)
@DisplayName("가게 사진 API")
class StoreImageControllerTest {

    private static final byte[] CONTENT = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0x10, 'J', 'F', 'I', 'F'};
    private static final String FILE_ID = "3f2a9c1e-5b7d-4e8a-9c0f-1a2b3c4d5e6f";
    private static final String IMAGE_URL = "/api/files/" + FILE_ID;
    private static final String LIST_URL = "/api/stores/7/images";
    private static final long OWNER_ID = 5L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtProvider jwtProvider;

    @MockitoBean
    private UploadFileService uploadFileService;

    private String bearer(MemberRole role) {
        return "Bearer " + jwtProvider.issue(OWNER_ID, role).value();
    }

    private void givenImage(UploadKind kind, FileType type) {
        when(uploadFileService.loadImage(any(), any()))
                .thenReturn(new StoredFile(FILE_ID, kind, type, CONTENT.length, new ByteArrayResource(CONTENT)));
    }

    @Nested
    @DisplayName("로그인한 점주·관리자만 닿는다")
    class WhoCanCall {

        @Test
        @DisplayName("토큰이 없으면 목록도 사진도 401 이고 서비스에 닿지 않는다 — 예전처럼 누구나 보지 못한다")
        void rejectsAnonymous() throws Exception {
            mockMvc.perform(get(LIST_URL)).andExpect(status().isUnauthorized());
            mockMvc.perform(get(IMAGE_URL)).andExpect(status().isUnauthorized());
            mockMvc.perform(get(IMAGE_URL).header(HttpHeaders.AUTHORIZATION, "Bearer not-a-token"))
                    .andExpect(status().isUnauthorized());

            verifyNoInteractions(uploadFileService);
        }

        @Test
        @DisplayName("가입 상태 확인용 토큰으로는 403 — 승인 전 신청자는 자기가 올린 사진도 이 길로 보지 못한다")
        void rejectsSignupStatusToken() throws Exception {
            String applicant = "Bearer " + jwtProvider.issueSignupStatus(OWNER_ID).value();

            mockMvc.perform(get(LIST_URL).header(HttpHeaders.AUTHORIZATION, applicant)).andExpect(status().isForbidden());
            mockMvc.perform(get(IMAGE_URL).header(HttpHeaders.AUTHORIZATION, applicant)).andExpect(status().isForbidden());

            verifyNoInteractions(uploadFileService);
        }

        @Test
        @DisplayName("점주 토큰이면 서비스까지 가고, 누가 부르는지(회원 ID·역할)를 넘긴다")
        void passesCallerToService() throws Exception {
            when(uploadFileService.getStoreImages(any(), any())).thenReturn(List.of());
            givenImage(UploadKind.STORE_IMAGE, FileType.JPEG);

            mockMvc.perform(get(LIST_URL).header(HttpHeaders.AUTHORIZATION, bearer(MemberRole.OWNER)))
                    .andExpect(status().isOk());
            mockMvc.perform(get(IMAGE_URL).header(HttpHeaders.AUTHORIZATION, bearer(MemberRole.OWNER)))
                    .andExpect(status().isOk());

            AuthMember owner = new AuthMember(OWNER_ID, MemberRole.OWNER);
            verify(uploadFileService).getStoreImages(7L, owner);
            verify(uploadFileService).loadImage(FILE_ID, owner);
        }

        @Test
        @DisplayName("열린 것은 조회(GET)와 파일 ID 모양(소문자 UUID)·숫자 가게 번호뿐이다 — 나머지는 관리자 전용 규칙에 걸린다")
        void onlyGetWithWellFormedIdIsOpen() throws Exception {
            String owner = bearer(MemberRole.OWNER);
            List<String> malformed = List.of("/api/files/11", "/api/files/abc",
                    "/api/files/" + FILE_ID.toUpperCase(Locale.ROOT), IMAGE_URL + "/extra", "/api/stores/abc/images");
            for (String url : malformed) {
                mockMvc.perform(get(url)).andExpect(status().isUnauthorized());
                mockMvc.perform(get(url).header(HttpHeaders.AUTHORIZATION, owner)).andExpect(status().isForbidden());
            }
            mockMvc.perform(post(IMAGE_URL).header(HttpHeaders.AUTHORIZATION, owner)).andExpect(status().isForbidden());
            mockMvc.perform(delete(IMAGE_URL).header(HttpHeaders.AUTHORIZATION, owner)).andExpect(status().isForbidden());
            mockMvc.perform(post(LIST_URL).header(HttpHeaders.AUTHORIZATION, owner)).andExpect(status().isForbidden());

            verifyNoInteractions(uploadFileService);
        }
    }

    @Nested
    @DisplayName("사진 목록")
    class Listing {

        @Test
        @DisplayName("파일 ID 와 종류만 준다 — 올린 사람·시각·크기·저장 이름은 없다")
        void listsIdsAndKinds() throws Exception {
            when(uploadFileService.getStoreImages(any(), any())).thenReturn(List.of(
                    new StoreImageResponse(FILE_ID, UploadKind.STORE_IMAGE),
                    new StoreImageResponse("9c8b7a6d-5e4f-4a3b-8c2d-1e0f9a8b7c6d", UploadKind.MENU_IMAGE)));

            mockMvc.perform(get(LIST_URL).header(HttpHeaders.AUTHORIZATION, bearer(MemberRole.OWNER)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(2))
                    .andExpect(jsonPath("$[0].fileId").value(FILE_ID))
                    .andExpect(jsonPath("$[0].kind").value("STORE_IMAGE"))
                    .andExpect(jsonPath("$[1].kind").value("MENU_IMAGE"))
                    .andExpect(jsonPath("$[0].memberId").doesNotExist())
                    .andExpect(jsonPath("$[0].uploadedAt").doesNotExist())
                    .andExpect(jsonPath("$[0].storedName").doesNotExist());
        }

        @Test
        @DisplayName("연결되지 않은 가게면 403, 관리자가 없는 가게를 부르면 404 — 서비스가 정한 답을 그대로 낸다")
        void mapsServiceErrors() throws Exception {
            when(uploadFileService.getStoreImages(any(), any())).thenThrow(new CustomException(ErrorCode.FORBIDDEN));
            mockMvc.perform(get(LIST_URL).header(HttpHeaders.AUTHORIZATION, bearer(MemberRole.OWNER)))
                    .andExpect(status().isForbidden());

            doThrow(new CustomException(ErrorCode.STORE_NOT_FOUND)).when(uploadFileService).getStoreImages(any(), any());
            mockMvc.perform(get(LIST_URL).header(HttpHeaders.AUTHORIZATION, bearer(MemberRole.ADMIN)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.type").value(ErrorCode.STORE_NOT_FOUND.getType().toString()));
        }
    }

    @Nested
    @DisplayName("사진 열기")
    class Open {

        @Test
        @DisplayName("형식은 서버가 가린 값이고 화면에 띄울 수 있게 준다. 보는 사람 브라우저에만 5분 둔다 — 공유 캐시에는 남기지 않는다")
        void opensImage() throws Exception {
            givenImage(UploadKind.STORE_IMAGE, FileType.JPEG);

            mockMvc.perform(get(IMAGE_URL).header(HttpHeaders.AUTHORIZATION, bearer(MemberRole.OWNER)))
                    .andExpect(status().isOk())
                    .andExpect(content().contentType(MediaType.IMAGE_JPEG))
                    .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                    .andExpect(header().string("Content-Security-Policy", "default-src 'none'; sandbox"))
                    .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                            "inline; filename=\"store-image-" + FILE_ID + ".jpg\""))
                    .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "max-age=300, private"))
                    .andExpect(content().bytes(CONTENT));
        }

        @Test
        @DisplayName("볼 수 없는 사진이면 404 file-not-found — 사업자등록증도, 다른 가게 사진도, 없는 ID 도 같은 답이다")
        void notVisible() throws Exception {
            when(uploadFileService.loadImage(any(), any())).thenThrow(new CustomException(ErrorCode.FILE_NOT_FOUND));

            mockMvc.perform(get(IMAGE_URL).header(HttpHeaders.AUTHORIZATION, bearer(MemberRole.OWNER)))
                    .andExpect(status().isNotFound())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.type").value(ErrorCode.FILE_NOT_FOUND.getType().toString()))
                    .andExpect(jsonPath("$.detail").doesNotExist());
        }

        @Test
        @DisplayName("가게 사진 경로는 가게 사진용 조회만 부른다 — 관리자 토큰이어도 관리자용 조회(사업자등록증까지 꺼내는 길)에는 닿지 않는다")
        void neverUsesAdminLookup() throws Exception {
            givenImage(UploadKind.STORE_IMAGE, FileType.JPEG);

            mockMvc.perform(get(IMAGE_URL).header(HttpHeaders.AUTHORIZATION, bearer(MemberRole.ADMIN)))
                    .andExpect(status().isOk());

            verify(uploadFileService).loadImage(FILE_ID, new AuthMember(OWNER_ID, MemberRole.ADMIN));
            verify(uploadFileService, never()).load(any());
        }

        @Test
        @DisplayName("관리자 경로는 점주에게 여전히 403 — 가게 사진 경로를 열면서 함께 열리지 않았다")
        void adminPathStaysClosed() throws Exception {
            mockMvc.perform(get("/api/admin/files/" + FILE_ID)).andExpect(status().isUnauthorized());
            mockMvc.perform(get("/api/admin/files/" + FILE_ID).header(HttpHeaders.AUTHORIZATION, bearer(MemberRole.OWNER)))
                    .andExpect(status().isForbidden());

            verifyNoInteractions(uploadFileService);
        }
    }
}
