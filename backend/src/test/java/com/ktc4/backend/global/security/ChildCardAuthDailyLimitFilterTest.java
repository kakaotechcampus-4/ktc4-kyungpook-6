package com.ktc4.backend.global.security;

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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 아동 카드 인증의 하루 한도 확인. 10분 한도를 넉넉히 풀고(100번) 하루 2번으로 낮춰 본다.
 *
 * <p>검사를 생략해 아무 16자리나 아동이 되므로, 한 곳에서 아동 행을 끝없이 만드는 것을 이 한도가 막는다.
 * 10분 한도는 {@link ChildCardAuthRateLimitFilterTest} 가 본다. 한도 값이 달라 컨텍스트를 따로 쓴다.
 */
@WebMvcTest(ChildAuthController.class)
@Import(SecurityConfig.class)
@TestPropertySource(properties = {
        "child.card-auth.rate-limit.max-attempts=100", "child.card-auth.rate-limit.window-minutes=10",
        "child.card-auth.rate-limit.max-attempts-per-day=2"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
@DisplayName("아동 카드 인증 하루 한도")
class ChildCardAuthDailyLimitFilterTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ChildAuthService childAuthService;

    private static MockHttpServletRequestBuilder cardAuth(String ip, int n) {
        return post("/api/auth/children/card").contentType(MediaType.APPLICATION_JSON)
                .content("{\"cardNumber\":\"" + String.format("9000%012d", n) + "\"}")
                .with(request -> {
                    request.setRemoteAddr(ip);
                    return request;
                });
    }

    @Test
    @DisplayName("하루 한도를 넘으면 429 이다 — 10분 한도가 남아 있어도. 카드마다가 아니라 곳마다 센다")
    void rejectsOverDailyLimit() throws Exception {
        for (int i = 0; i < 2; i++) {
            mockMvc.perform(cardAuth("203.0.113.80", i)).andExpect(status().isOk());
        }

        String retryAfter = mockMvc.perform(cardAuth("203.0.113.80", 9))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.type").value(ErrorCode.TOO_MANY_REQUESTS.getType().toString()))
                .andReturn().getResponse().getHeader(HttpHeaders.RETRY_AFTER);

        assertThat(Long.parseLong(retryAfter)).isBetween(601L, 86_400L);   // 10분 한도가 아니라 하루 한도에 걸렸다
    }

    @Test
    @DisplayName("IP 가 다르면 하루 수도 따로 센다")
    void countsPerIp() throws Exception {
        for (int i = 0; i < 2; i++) {
            mockMvc.perform(cardAuth("203.0.113.90", i));
        }
        mockMvc.perform(cardAuth("203.0.113.90", 5)).andExpect(status().isTooManyRequests());

        mockMvc.perform(cardAuth("198.51.100.90", 6)).andExpect(status().isOk());
    }
}
