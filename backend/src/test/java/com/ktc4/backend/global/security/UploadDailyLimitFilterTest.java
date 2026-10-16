package com.ktc4.backend.global.security;

import com.ktc4.backend.domain.auth.controller.AuthController;
import com.ktc4.backend.domain.auth.service.AuthService;
import com.ktc4.backend.domain.file.controller.FileUploadController;
import com.ktc4.backend.domain.file.dto.UploadResponse;
import com.ktc4.backend.domain.file.enums.FileType;
import com.ktc4.backend.domain.file.enums.UploadKind;
import com.ktc4.backend.domain.file.service.UploadFileService;
import com.ktc4.backend.global.error.CustomException;
import com.ktc4.backend.global.error.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static com.ktc4.backend.support.SignupMultipart.jpeg;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 파일 업로드의 하루 상한 확인. 10분 한도를 넉넉히 풀고(100번) 하루에 성공 2개로 낮춰 실제 보안 필터 체인으로 본다.
 *
 * <p>10분 한도는 {@link UploadRateLimitFilterTest} 가 본다. 한도 값이 달라 컨텍스트를 따로 쓴다.
 * 횟수는 필터가 메모리에 들고 있어 테스트마다 컨텍스트를 새로 만든다. IP 는 문서용 대역(RFC 5737)의 값이다.
 */
@WebMvcTest({FileUploadController.class, AuthController.class})
@Import(SecurityConfig.class)
@TestPropertySource(properties = {
        "upload.rate-limit.max-attempts=100", "upload.rate-limit.window-minutes=10",
        "upload.rate-limit.max-uploads-per-day=2"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
@DisplayName("파일 업로드 하루 상한")
class UploadDailyLimitFilterTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UploadFileService uploadFileService;

    @MockitoBean
    private AuthService authService;

    @BeforeEach
    void setUp() {
        doReturn(new UploadResponse("3f2a9c1e-5b7d-4e8a-9c0f-1a2b3c4d5e6f", UploadKind.STORE_IMAGE, FileType.JPEG, 100L))
                .when(uploadFileService).upload(any(), any());
    }

    private static MockHttpServletRequestBuilder uploadFrom(String ip) {
        return multipart("/api/files").file(jpeg("file")).param("kind", "STORE_IMAGE")
                .with(request -> {
                    request.setRemoteAddr(ip);
                    return request;
                });
    }


    @Test
    @DisplayName("하루에 올리는 데 성공한 수가 상한을 넘으면 429 이고 서비스에 닿지 않는다 — 10분 한도가 남아 있어도")
    void rejectsOverDailyLimit() throws Exception {
        for (int i = 0; i < 2; i++) {
            mockMvc.perform(uploadFrom("203.0.113.80")).andExpect(status().isCreated());
        }

        String retryAfter = mockMvc.perform(uploadFrom("203.0.113.80"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.type").value(ErrorCode.TOO_MANY_REQUESTS.getType().toString()))
                .andReturn().getResponse().getHeader(HttpHeaders.RETRY_AFTER);

        assertThat(Long.parseLong(retryAfter)).isBetween(601L, 86_400L);   // 10분 한도가 아니라 하루 한도에 걸렸다
        verify(uploadFileService, times(2)).upload(any(), any());
    }

    @Test
    @DisplayName("실패한 업로드는 하루 수에 세지 않는다 — 쌓이는 것은 성공한 업로드뿐이라, 잘못 고른 파일 때문에 막히지 않게")
    void countsOnlySuccesses() throws Exception {
        doThrow(new CustomException(ErrorCode.UNSUPPORTED_FILE_TYPE)).when(uploadFileService).upload(any(), any());
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(uploadFrom("203.0.113.90")).andExpect(status().isBadRequest());
        }

        doReturn(new UploadResponse("3f2a9c1e-5b7d-4e8a-9c0f-1a2b3c4d5e6f", UploadKind.STORE_IMAGE, FileType.JPEG, 100L))
                .when(uploadFileService).upload(any(), any());
        for (int i = 0; i < 2; i++) {
            mockMvc.perform(uploadFrom("203.0.113.90")).andExpect(status().isCreated());
        }
        mockMvc.perform(uploadFrom("203.0.113.90")).andExpect(status().isTooManyRequests());
    }

    @Test
    @DisplayName("IP 가 다르면 하루 수도 따로 센다")
    void countsPerIp() throws Exception {
        for (int i = 0; i < 2; i++) {
            mockMvc.perform(uploadFrom("203.0.113.100")).andExpect(status().isCreated());
        }
        mockMvc.perform(uploadFrom("203.0.113.100")).andExpect(status().isTooManyRequests());

        mockMvc.perform(uploadFrom("203.0.113.101")).andExpect(status().isCreated());
    }
}
