package com.ktc4.backend.global.security;

import com.ktc4.backend.domain.auth.controller.AuthController;
import com.ktc4.backend.domain.auth.service.AuthService;
import com.ktc4.backend.domain.file.controller.BizCertExtractionController;
import com.ktc4.backend.domain.file.controller.FileUploadController;
import com.ktc4.backend.domain.file.dto.UploadResponse;
import com.ktc4.backend.domain.file.enums.FileType;
import com.ktc4.backend.domain.file.enums.UploadKind;
import com.ktc4.backend.domain.file.service.UploadFileService;
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
import java.util.Locale;

import static com.ktc4.backend.support.SignupMultipart.jpeg;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 사업자등록증 읽기 횟수 제한 확인. 한도를 낮춰(같은 곳 10분 4번, 같은 파일 2번) 실제 보안 필터 체인으로 본다.
 *
 * <p>읽기 API 는 아직 501 이다 — 필터를 지나면 501, 막히면 429 다. 하루 상한은 {@link BizCertReadDailyLimitFilterTest} 가 본다.
 * 횟수는 필터가 메모리에 들고 있어 테스트마다 컨텍스트를 새로 만든다. IP 는 문서용 대역(RFC 5737)의 값이다.
 */
@WebMvcTest({BizCertExtractionController.class, FileUploadController.class, AuthController.class})
@Import(SecurityConfig.class)
@TestPropertySource(properties = {
        "biz-cert-extraction.rate-limit.max-attempts=4", "biz-cert-extraction.rate-limit.window-minutes=10",
        "biz-cert-extraction.rate-limit.max-attempts-per-day=100",
        "biz-cert-extraction.rate-limit.max-attempts-per-file=2", "biz-cert-extraction.rate-limit.file-window-hours=3",
        "signup.rate-limit.trusted-proxies=1"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
@DisplayName("사업자등록증 읽기 횟수 제한")
class BizCertReadRateLimitFilterTest {

    private static final String PROXY = "192.0.2.1";
    private static final String FILE = "3f2a9c1e-5b7d-4e8a-9c0f-1a2b3c4d5e6f";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UploadFileService uploadFileService;

    @MockitoBean
    private AuthService authService;

    @BeforeEach
    void setUp() {
        when(uploadFileService.upload(any(), any())).thenReturn(new UploadResponse(
                FILE, UploadKind.BIZ_CERT, FileType.PDF, 100L));
    }

    // 서로 다른 파일 ID — 같은 곳 한도만 보려고 파일마다 한 번씩 부를 때 쓴다
    private static String file(int n) {
        return String.format("3f2a9c1e-5b7d-4e8a-9c0f-%012d", n);
    }

    private static MockHttpServletRequestBuilder at(String ip, MockHttpServletRequestBuilder request) {
        return request.with(r -> {
            r.setRemoteAddr(ip);
            return r;
        });
    }

    private static MockHttpServletRequestBuilder read(String ip, String fileId) {
        return at(ip, post("/api/files/" + fileId + "/biz-cert-extraction"));
    }

    @Test
    @DisplayName("같은 곳에서 한도를 넘기면 429 too-many-requests 이고, 팀 에러 규격과 Retry-After 가 실린다 — 파일이 달라도")
    void rejectsOverLimitPerPlace() throws Exception {
        for (int i = 0; i < 4; i++) {
            mockMvc.perform(read("203.0.113.10", file(i))).andExpect(status().isNotImplemented());
        }

        String retryAfter = mockMvc.perform(read("203.0.113.10", file(9)))
                .andExpect(status().isTooManyRequests())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value(ErrorCode.TOO_MANY_REQUESTS.getType().toString()))
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(header().exists(HttpHeaders.RETRY_AFTER))
                .andReturn().getResponse().getHeader(HttpHeaders.RETRY_AFTER);

        assertThat(Long.parseLong(retryAfter)).isBetween(1L, 600L);
    }

    @Test
    @DisplayName("같은 파일 ID 는 어디서 부르든 함께 센다 — 곳을 바꿔 가며 같은 파일을 되풀이해 읽지 못하게")
    void countsSameFileFromAnyPlace() throws Exception {
        mockMvc.perform(read("203.0.113.20", FILE)).andExpect(status().isNotImplemented());
        mockMvc.perform(read("198.51.100.20", FILE)).andExpect(status().isNotImplemented());

        String retryAfter = mockMvc.perform(read("203.0.113.21", FILE))
                .andExpect(status().isTooManyRequests())
                .andReturn().getResponse().getHeader(HttpHeaders.RETRY_AFTER);

        assertThat(Long.parseLong(retryAfter)).isBetween(601L, 3 * 3600L);   // 10분 한도가 아니라 파일 한도에 걸렸다
        mockMvc.perform(read("203.0.113.21", file(1))).andExpect(status().isNotImplemented());   // 다른 파일은 그대로
    }

    @Test
    @DisplayName("같은 곳 한도에 막힌 요청은 파일 ID 로 세지 않는다 — 한 곳이 남의 파일 몫을 깎지 못하게")
    void rejectedByPlaceDoesNotUseFileQuota() throws Exception {
        for (int i = 0; i < 4; i++) {
            mockMvc.perform(read("203.0.113.30", file(i)));
        }
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(read("203.0.113.30", FILE)).andExpect(status().isTooManyRequests());
        }

        mockMvc.perform(read("198.51.100.30", FILE)).andExpect(status().isNotImplemented());
        mockMvc.perform(read("198.51.100.31", FILE)).andExpect(status().isNotImplemented());
    }

    @Test
    @DisplayName("파일 ID 모양이 틀리거나 끝에 / 가 붙은 경로는 세지 않는다 — 보안 규칙에 막혀 모델까지 가지 않는다")
    void doesNotCountMalformedPaths() throws Exception {
        for (String bad : new String[]{"abc", "11", FILE.toUpperCase(Locale.ROOT), FILE + "0", FILE.substring(1)}) {
            for (int i = 0; i < 2; i++) {
                mockMvc.perform(read("203.0.113.35", bad)).andExpect(status().isUnauthorized());
            }
        }
        // 끝에 / 가 붙은 경로도 열린 규칙에 걸리지 않고, 세지도 않는다
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(at("203.0.113.35", post("/api/files/" + FILE + "/biz-cert-extraction/")))
                    .andExpect(status().isUnauthorized());
        }

        for (int i = 0; i < 4; i++) {
            mockMvc.perform(read("203.0.113.35", file(i))).andExpect(status().isNotImplemented());
        }
    }

    @Test
    @DisplayName("IP 가 다르면 따로 센다")
    void countsPerIp() throws Exception {
        for (int i = 0; i < 4; i++) {
            mockMvc.perform(read("203.0.113.40", file(i)));
        }

        mockMvc.perform(read("203.0.113.40", file(5))).andExpect(status().isTooManyRequests());
        mockMvc.perform(read("198.51.100.40", file(6))).andExpect(status().isNotImplemented());
    }

    @Test
    @DisplayName("프록시 뒤에서는 X-Forwarded-For 의 보낸 쪽으로 센다 — 모두 프록시 주소로 세면 한 사람이 모두를 막는다")
    void countsClientBehindProxy() throws Exception {
        for (int i = 0; i < 4; i++) {
            mockMvc.perform(read(PROXY, file(i)).header("X-Forwarded-For", "203.0.113.50"));
        }

        mockMvc.perform(read(PROXY, file(5)).header("X-Forwarded-For", "203.0.113.50"))
                .andExpect(status().isTooManyRequests());
        mockMvc.perform(read(PROXY, file(6)).header("X-Forwarded-For", "198.51.100.50"))
                .andExpect(status().isNotImplemented());
    }

    @Test
    @DisplayName("경로의 글자를 인코딩해 보내도 같은 읽기로 센다")
    void countsEncodedPathToo() throws Exception {
        for (int i = 0; i < 4; i++) {
            mockMvc.perform(at("203.0.113.60", post(URI.create("/api/files/" + file(i) + "/biz-cert-extractio%6E"))))
                    .andExpect(status().isNotImplemented());
        }

        mockMvc.perform(read("203.0.113.60", file(5))).andExpect(status().isTooManyRequests());
    }

    @Test
    @DisplayName("읽기만 센다 — 읽기가 막힌 IP 에서도 업로드·가입 신청과 같은 경로의 다른 메서드는 각자의 규칙대로 간다")
    void limitsOnlyBizCertRead() throws Exception {
        for (int i = 0; i < 4; i++) {
            mockMvc.perform(read("203.0.113.70", file(i)));
        }
        mockMvc.perform(read("203.0.113.70", file(5))).andExpect(status().isTooManyRequests());

        mockMvc.perform(at("203.0.113.70", multipart("/api/files").file(jpeg("file")).param("kind", "BIZ_CERT")))
                .andExpect(status().isCreated());
        mockMvc.perform(at("203.0.113.70", post("/api/auth/owners/signup")
                        .contentType(MediaType.APPLICATION_JSON).content("{}")))
                .andExpect(status().isBadRequest());
        // 같은 경로라도 POST 가 아니면 이 제한이 아니라 관리자 전용 규칙에 걸린다
        mockMvc.perform(at("203.0.113.70", get("/api/files/" + file(5) + "/biz-cert-extraction")))
                .andExpect(status().isUnauthorized());
    }
}
