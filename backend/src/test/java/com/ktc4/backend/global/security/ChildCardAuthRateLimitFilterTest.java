package com.ktc4.backend.global.security;

import com.ktc4.backend.domain.auth.controller.AuthController;
import com.ktc4.backend.domain.auth.service.AuthService;
import com.ktc4.backend.domain.child.controller.ChildAuthController;
import com.ktc4.backend.domain.child.service.ChildAuthService;
import com.ktc4.backend.global.error.ErrorCode;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 아동 카드 인증 횟수 제한 확인. 10분 한도를 3번으로 낮추고 하루 한도는 넉넉히(100번) 풀어 실제 보안 필터 체인으로 본다.
 *
 * <p>인증 구현은 가짜로 둔다 — 필터를 지나면 200, 막히면 429 다. 하루 한도는 {@link ChildCardAuthDailyLimitFilterTest} 가 본다.
 * 횟수는 필터가 메모리에 들고 있어 테스트마다 컨텍스트를 새로 만든다. IP 는 문서용 대역(RFC 5737)의 값이다.
 */
@WebMvcTest({ChildAuthController.class, AuthController.class})
@Import(SecurityConfig.class)
@TestPropertySource(properties = {
        "child.card-auth.rate-limit.max-attempts=3", "child.card-auth.rate-limit.window-minutes=10",
        "child.card-auth.rate-limit.max-attempts-per-day=100", "signup.rate-limit.trusted-proxies=1"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
@DisplayName("아동 카드 인증 횟수 제한")
class ChildCardAuthRateLimitFilterTest {

    private static final String PROXY = "192.0.2.1";
    private static final String BODY = "{\"cardNumber\":\"1234567812345678\"}";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ChildAuthService childAuthService;

    @MockitoBean
    private AuthService authService;

    private static MockHttpServletRequestBuilder at(String ip, MockHttpServletRequestBuilder request) {
        return request.with(r -> {
            r.setRemoteAddr(ip);
            return r;
        });
    }

    private static MockHttpServletRequestBuilder cardAuth(String ip) {
        return at(ip, post("/api/auth/children/card").contentType(MediaType.APPLICATION_JSON).content(BODY));
    }

    @Test
    @DisplayName("같은 곳에서 한도를 넘기면 429 too-many-requests 이고, 팀 에러 규격과 Retry-After 가 실린다")
    void rejectsOverLimit() throws Exception {
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(cardAuth("203.0.113.10")).andExpect(status().isOk());
        }

        String retryAfter = mockMvc.perform(cardAuth("203.0.113.10"))
                .andExpect(status().isTooManyRequests())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value(ErrorCode.TOO_MANY_REQUESTS.getType().toString()))
                .andExpect(header().exists(HttpHeaders.RETRY_AFTER))
                .andReturn().getResponse().getHeader(HttpHeaders.RETRY_AFTER);

        assertThat(Long.parseLong(retryAfter)).isBetween(1L, 600L);
    }

    @Test
    @DisplayName("형식이 틀려 400 으로 끝난 요청도 센다 — 번호를 바꿔 가며 대입하는 것을 늦추려는 것이라")
    void countsRejectedRequestsToo() throws Exception {
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(at("203.0.113.15", post("/api/auth/children/card")
                            .contentType(MediaType.APPLICATION_JSON).content("{\"cardNumber\":\"12\"}")))
                    .andExpect(status().isBadRequest());
        }

        mockMvc.perform(cardAuth("203.0.113.15")).andExpect(status().isTooManyRequests());
    }

    @Test
    @DisplayName("IP 가 다르면 따로 센다")
    void countsPerIp() throws Exception {
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(cardAuth("203.0.113.20"));
        }

        mockMvc.perform(cardAuth("203.0.113.20")).andExpect(status().isTooManyRequests());
        mockMvc.perform(cardAuth("198.51.100.20")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("프록시 뒤에서는 X-Forwarded-For 의 보낸 쪽으로 센다 — 모두 프록시 주소로 세면 한 아이가 모두를 막는다")
    void countsClientBehindProxy() throws Exception {
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(cardAuth(PROXY).header("X-Forwarded-For", "203.0.113.30"));
        }

        mockMvc.perform(cardAuth(PROXY).header("X-Forwarded-For", "203.0.113.30")).andExpect(status().isTooManyRequests());
        mockMvc.perform(cardAuth(PROXY).header("X-Forwarded-For", "198.51.100.30")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("카드 인증만 센다 — 막힌 IP 에서도 로그인은 그대로 간다")
    void limitsOnlyCardAuth() throws Exception {
        for (int i = 0; i < 4; i++) {
            mockMvc.perform(cardAuth("203.0.113.40"));
        }

        mockMvc.perform(at("203.0.113.40", post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"owner@example.com\",\"password\":\"password1234\"}")))
                .andExpect(status().isOk());
    }
}
