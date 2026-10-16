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
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.net.URI;

import static com.ktc4.backend.support.SignupMultipart.jpeg;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 파일 업로드 횟수 제한 확인. 한도를 낮춰(10분에 3번) 실제 보안 필터 체인으로 본다.
 *
 * <p>횟수는 필터가 메모리에 들고 있어 테스트끼리 섞인다. 그래서 테스트마다 컨텍스트를 새로 만든다.
 * IP 는 문서용으로 예약된 대역(RFC 5737)의 값이다 — 실제 기기에 배정되지 않는다.
 */
@WebMvcTest({FileUploadController.class, AuthController.class})
@Import(SecurityConfig.class)
@TestPropertySource(properties = {
        "upload.rate-limit.max-attempts=3", "upload.rate-limit.window-minutes=10",
        "signup.rate-limit.trusted-proxies=1"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
@DisplayName("파일 업로드 횟수 제한")
class UploadRateLimitFilterTest {

    private static final String PROXY = "192.0.2.1";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UploadFileService uploadFileService;

    @MockitoBean
    private AuthService authService;

    @BeforeEach
    void setUp() {
        when(uploadFileService.upload(any(), any())).thenReturn(new UploadResponse(
                "3f2a9c1e-5b7d-4e8a-9c0f-1a2b3c4d5e6f", UploadKind.STORE_IMAGE, FileType.JPEG, 100L));
    }

    private static MockHttpServletRequestBuilder uploadTo(URI uri, String ip) {
        return multipart(uri).file(jpeg("file")).param("kind", "STORE_IMAGE")
                .with(request -> {
                    request.setRemoteAddr(ip);
                    return request;
                });
    }

    private static MockHttpServletRequestBuilder uploadFrom(String ip) {
        return uploadTo(URI.create("/api/files"), ip);
    }

    @Test
    @DisplayName("같은 IP 에서 한도를 넘기면 429 too-many-requests 이고, 팀 에러 규격과 Retry-After 가 실린다")
    void rejectsOverLimit() throws Exception {
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(uploadFrom("203.0.113.10")).andExpect(status().isCreated());
        }

        String retryAfter = mockMvc.perform(uploadFrom("203.0.113.10"))
                .andExpect(status().isTooManyRequests())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value(ErrorCode.TOO_MANY_REQUESTS.getType().toString()))
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(header().exists(HttpHeaders.RETRY_AFTER))
                .andReturn().getResponse().getHeader(HttpHeaders.RETRY_AFTER);

        assertThat(Long.parseLong(retryAfter)).isBetween(1L, 600L);
    }

    @Test
    @DisplayName("거절된 업로드는 서비스에 닿지 않는다 — 디스크에 쓰기 전에 막는다")
    void rejectedRequestNeverReachesService() throws Exception {
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(uploadFrom("203.0.113.20"));
        }

        verify(uploadFileService, times(3)).upload(any(), any());
    }

    @Test
    @DisplayName("실패한 업로드도 센다 — 받지 않는 파일을 되풀이해 보내도 서버가 매번 읽지 않게")
    void countsFailuresToo() throws Exception {
        when(uploadFileService.upload(any(), any())).thenThrow(new CustomException(ErrorCode.UNSUPPORTED_FILE_TYPE));
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(uploadFrom("203.0.113.30")).andExpect(status().isBadRequest());
        }

        mockMvc.perform(uploadFrom("203.0.113.30")).andExpect(status().isTooManyRequests());
    }

    @Test
    @DisplayName("IP 가 다르면 따로 센다")
    void countsPerIp() throws Exception {
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(uploadFrom("203.0.113.40"));
        }

        mockMvc.perform(uploadFrom("203.0.113.40")).andExpect(status().isTooManyRequests());
        mockMvc.perform(uploadFrom("198.51.100.40")).andExpect(status().isCreated());
    }

    @Test
    @DisplayName("프록시 뒤에서는 X-Forwarded-For 의 보낸 쪽으로 센다 — 모두 프록시 주소로 세면 한 사람이 모두를 막는다")
    void countsClientBehindProxy() throws Exception {
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(uploadFrom(PROXY).header("X-Forwarded-For", "203.0.113.50"));
        }

        mockMvc.perform(uploadFrom(PROXY).header("X-Forwarded-For", "203.0.113.50")).andExpect(status().isTooManyRequests());
        mockMvc.perform(uploadFrom(PROXY).header("X-Forwarded-For", "198.51.100.50")).andExpect(status().isCreated());
    }

    @Test
    @DisplayName("경로의 글자를 인코딩해 보내도 같은 업로드로 센다")
    void countsEncodedPathToo() throws Exception {
        URI encoded = URI.create("/api/fil%65s");
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(uploadTo(encoded, "203.0.113.60")).andExpect(status().isCreated());
        }

        mockMvc.perform(uploadTo(encoded, "203.0.113.60")).andExpect(status().isTooManyRequests());
        mockMvc.perform(uploadFrom("203.0.113.60")).andExpect(status().isTooManyRequests());
    }

    @Test
    @DisplayName("업로드만 센다 — 업로드가 막힌 IP 에서도 가입 신청·사진 조회는 각자의 규칙대로 간다")
    void limitsOnlyUpload() throws Exception {
        for (int i = 0; i < 4; i++) {
            mockMvc.perform(uploadFrom("203.0.113.70"));
        }
        mockMvc.perform(uploadFrom("203.0.113.70")).andExpect(status().isTooManyRequests());

        // 가입 신청은 글자 정보 검증까지 간다(빈 본문이라 400). 가게 사진 조회는 토큰이 없어 401
        mockMvc.perform(post("/api/auth/owners/signup").contentType(MediaType.APPLICATION_JSON).content("{}")
                        .with(request -> {
                            request.setRemoteAddr("203.0.113.70");
                            return request;
                        }))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/files/3f2a9c1e-5b7d-4e8a-9c0f-1a2b3c4d5e6f").with(request -> {
                    request.setRemoteAddr("203.0.113.70");
                    return request;
                }))
                .andExpect(status().isUnauthorized());
    }
}
