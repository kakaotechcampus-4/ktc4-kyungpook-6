package com.ktc4.backend.domain.file.controller;

import com.ktc4.backend.domain.file.dto.StoreImageResponse;
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

import static org.mockito.ArgumentMatchers.any;
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
 * 가게 사진 공개 API 확인. 실제 보안 규칙({@link SecurityConfig})으로 본다 — 로그인 없이 열리는 경로가
 * 의도한 두 개뿐인지, 그리고 관리자 전용 조회와 섞이지 않는지.
 *
 * <p>관리자용 진입점도 함께 띄운다. 공개 경로({@code /api/files/{id}})와 관리자 경로({@code /api/admin/files/{id}})가
 * 서로 다른 서비스 메서드를 부르는지 보려는 것이다.
 */
@WebMvcTest({FilePublicController.class, FileAdminController.class})
@Import(SecurityConfig.class)
@DisplayName("가게 사진 공개 API")
class FilePublicControllerTest {

    private static final byte[] CONTENT = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0x10, 'J', 'F', 'I', 'F'};

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtProvider jwtProvider;

    @MockitoBean
    private UploadFileService uploadFileService;

    private void givenPublicImage(long fileId, UploadKind kind, FileType type) {
        when(uploadFileService.loadPublic(fileId))
                .thenReturn(new StoredFile(fileId, kind, type, CONTENT.length, new ByteArrayResource(CONTENT)));
    }

    @Test
    @DisplayName("가게 사진 목록은 토큰 없이 볼 수 있다 — 파일 ID 와 종류만 준다")
    void listsImagesWithoutToken() throws Exception {
        when(uploadFileService.getStoreImages(7L)).thenReturn(List.of(
                new StoreImageResponse(11L, UploadKind.STORE_IMAGE),
                new StoreImageResponse(12L, UploadKind.MENU_IMAGE)));

        mockMvc.perform(get("/api/stores/7/images"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].fileId").value(11))
                .andExpect(jsonPath("$[0].kind").value("STORE_IMAGE"))
                .andExpect(jsonPath("$[1].kind").value("MENU_IMAGE"))
                // 누구나 보는 응답이다. 올린 사람·시각·크기·저장 이름은 없다
                .andExpect(jsonPath("$[0].memberId").doesNotExist())
                .andExpect(jsonPath("$[0].uploadedAt").doesNotExist())
                .andExpect(jsonPath("$[0].storedName").doesNotExist());
    }

    @Test
    @DisplayName("없는 가게면 404 store-not-found")
    void missingStore() throws Exception {
        when(uploadFileService.getStoreImages(7L)).thenThrow(new CustomException(ErrorCode.STORE_NOT_FOUND));

        mockMvc.perform(get("/api/stores/7/images"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value(ErrorCode.STORE_NOT_FOUND.getType().toString()));
    }

    @Test
    @DisplayName("사진은 토큰 없이 열린다 — 형식은 서버가 가린 값이고, 화면에 바로 띄울 수 있게 준다")
    void opensImageWithoutToken() throws Exception {
        givenPublicImage(11L, UploadKind.STORE_IMAGE, FileType.JPEG);

        mockMvc.perform(get("/api/files/11"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_JPEG))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Content-Security-Policy", "default-src 'none'; sandbox"))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"store-image-11.jpg\""))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "max-age=300, public"))
                .andExpect(content().bytes(CONTENT));
    }

    @Test
    @DisplayName("점주·관리자 토큰을 붙여도, 틀린 토큰을 붙여도 똑같이 열린다")
    void opensImageRegardlessOfToken() throws Exception {
        givenPublicImage(11L, UploadKind.MENU_IMAGE, FileType.PNG);

        mockMvc.perform(get("/api/files/11")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwtProvider.issue(1L, MemberRole.OWNER).value()))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_PNG));
        mockMvc.perform(get("/api/files/11").header(HttpHeaders.AUTHORIZATION, "Bearer not-a-token"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("공개된 사진이 아니면 404 file-not-found — 사업자등록증도, 승인 전 사진도, 없는 번호도 같은 답이다")
    void notPublic() throws Exception {
        when(uploadFileService.loadPublic(12L)).thenThrow(new CustomException(ErrorCode.FILE_NOT_FOUND));

        mockMvc.perform(get("/api/files/12"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value(ErrorCode.FILE_NOT_FOUND.getType().toString()))
                .andExpect(jsonPath("$.detail").doesNotExist());
    }

    @Test
    @DisplayName("공개 경로는 공개용 조회만 부른다 — 관리자용 조회(사업자등록증까지 꺼내는 길)에는 닿지 않는다")
    void publicPathNeverUsesAdminLookup() throws Exception {
        givenPublicImage(11L, UploadKind.STORE_IMAGE, FileType.JPEG);

        mockMvc.perform(get("/api/files/11")).andExpect(status().isOk());

        verify(uploadFileService).loadPublic(11L);
        verify(uploadFileService, never()).load(any());
    }

    @Test
    @DisplayName("관리자 경로는 여전히 토큰 없이 401 — 공개 경로를 열면서 함께 열리지 않았다")
    void adminPathStaysClosed() throws Exception {
        mockMvc.perform(get("/api/admin/files/11")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/admin/owners/3/files")).andExpect(status().isUnauthorized());

        verifyNoInteractions(uploadFileService);
    }

    @Test
    @DisplayName("열린 것은 조회(GET)와 숫자 번호뿐이다 — 다른 메서드와 숫자가 아닌 경로는 토큰 없이 401")
    void onlyGetWithNumericIdIsOpen() throws Exception {
        mockMvc.perform(post("/api/files/11")).andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/api/files/11")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/stores/7/images")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/files/abc")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/files/11/extra")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/stores/abc/images")).andExpect(status().isUnauthorized());

        verifyNoInteractions(uploadFileService);
    }
}
