package com.ktc4.backend.domain.file.controller;

import com.ktc4.backend.domain.file.dto.UploadResponse;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.multipart.MultipartFile;

import static com.ktc4.backend.support.SignupMultipart.jpeg;
import static com.ktc4.backend.support.SignupMultipart.pdf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 파일 업로드 API 확인. 실제 보안 규칙({@link SecurityConfig})으로 본다 — 로그인 없이 부를 수 있어야 한다.
 * 파일 내용 검사·저장은 서비스 테스트({@code UploadFileServiceTest})와 통합 테스트가 본다.
 */
@WebMvcTest(FileUploadController.class)
@Import(SecurityConfig.class)
@DisplayName("파일 업로드 API")
class FileUploadControllerTest {

    private static final String URL = "/api/files";
    private static final String FILE_ID = "7b1d0e4f-2c3a-4d5b-8e6f-9a0b1c2d3e4f";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtProvider jwtProvider;

    @MockitoBean
    private UploadFileService uploadFileService;

    private void givenUploaded() {
        when(uploadFileService.upload(any(), any()))
                .thenReturn(new UploadResponse(FILE_ID, UploadKind.BIZ_CERT, FileType.PDF, 14L));
    }

    @Test
    @DisplayName("토큰 없이 올리면 201 과 파일 ID·종류·형식·크기를 준다 — 저장한 이름·경로는 담지 않는다")
    void uploadsWithoutToken() throws Exception {
        givenUploaded();

        mockMvc.perform(multipart(URL).file(pdf("file")).param("kind", "BIZ_CERT"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.fileId").value(FILE_ID))
                .andExpect(jsonPath("$.kind").value("BIZ_CERT"))
                .andExpect(jsonPath("$.fileType").value("PDF"))
                .andExpect(jsonPath("$.sizeBytes").value(14))
                .andExpect(jsonPath("$.storedName").doesNotExist())
                .andExpect(jsonPath("$.originalFilename").doesNotExist());

        ArgumentCaptor<MultipartFile> sent = ArgumentCaptor.forClass(MultipartFile.class);
        verify(uploadFileService).upload(eq(UploadKind.BIZ_CERT), sent.capture());
        assertThat(sent.getValue().getBytes()).isEqualTo(pdf("file").getBytes());
    }

    @Test
    @DisplayName("점주 토큰이나 가입 상태 확인용 토큰을 붙여도 올릴 수 있다 — 막을 이유가 없다")
    void uploadsWithAnyToken() throws Exception {
        givenUploaded();

        mockMvc.perform(multipart(URL).file(jpeg("file")).param("kind", "STORE_IMAGE")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwtProvider.issue(1L, MemberRole.OWNER).value()))
                .andExpect(status().isCreated());
        mockMvc.perform(multipart(URL).file(jpeg("file")).param("kind", "STORE_IMAGE")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwtProvider.issueSignupStatus(3L).value()))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("kind·file 이 빠졌거나 kind 값이 틀리면 400 이고 서비스에 닿지 않는다")
    void rejectsMissingOrWrongParts() throws Exception {
        mockMvc.perform(multipart(URL).file(jpeg("file")))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        mockMvc.perform(multipart(URL).param("kind", "STORE_IMAGE"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(multipart(URL).file(jpeg("file")).param("kind", "PROFILE_IMAGE"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(multipart(URL).file(jpeg("image")).param("kind", "STORE_IMAGE"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(uploadFileService);
    }

    @Test
    @DisplayName("multipart 가 아니면 415 — JSON 으로 파일을 보낼 수 없다")
    void rejectsNonMultipart() throws Exception {
        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content("{\"kind\":\"STORE_IMAGE\"}"))
                .andExpect(status().isUnsupportedMediaType());

        verifyNoInteractions(uploadFileService);
    }

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource({"EMPTY_FILE, 400", "UNSUPPORTED_FILE_TYPE, 400", "FILE_TOO_LARGE, 413", "SIGNUP_TEMPORARILY_CLOSED, 503"})
    @DisplayName("서비스가 거절하면 그 에러를 팀 에러 규격으로 낸다")
    void mapsServiceErrors(ErrorCode errorCode, int status) throws Exception {
        when(uploadFileService.upload(any(), any())).thenThrow(new CustomException(errorCode));

        mockMvc.perform(multipart(URL).file(jpeg("file")).param("kind", "STORE_IMAGE"))
                .andExpect(status().is(status))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value(errorCode.getType().toString()))
                .andExpect(jsonPath("$.detail").doesNotExist());
    }
}
