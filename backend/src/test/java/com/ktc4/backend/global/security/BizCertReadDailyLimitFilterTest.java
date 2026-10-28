package com.ktc4.backend.global.security;

import com.ktc4.backend.domain.file.controller.BizCertExtractionController;
import com.ktc4.backend.domain.file.service.BizCertExtractionService;
import com.ktc4.backend.global.error.ErrorCode;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 사업자등록증 읽기의 하루 상한 확인. 10분 한도와 파일 한도를 넉넉히 풀고(100번) 하루 2번으로 낮춰 본다.
 *
 * <p>10분 한도·파일 한도는 {@link BizCertReadRateLimitFilterTest} 가 본다. 한도 값이 달라 컨텍스트를 따로 쓴다.
 * 읽기 구현은 가짜로 둔다 — 필터를 지나면 200 이다. IP 는 문서용 대역(RFC 5737)의 값이다.
 */
@WebMvcTest(BizCertExtractionController.class)
@Import(SecurityConfig.class)
@TestPropertySource(properties = {
        "biz-cert-extraction.rate-limit.max-attempts=100", "biz-cert-extraction.rate-limit.window-minutes=10",
        "biz-cert-extraction.rate-limit.max-attempts-per-day=2",
        "biz-cert-extraction.rate-limit.max-attempts-per-file=100"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
@DisplayName("사업자등록증 읽기 하루 상한")
class BizCertReadDailyLimitFilterTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private BizCertExtractionService bizCertExtractionService;

    private static MockHttpServletRequestBuilder read(String ip, int n) {
        return post("/api/files/" + String.format("3f2a9c1e-5b7d-4e8a-9c0f-%012d", n) + "/biz-cert-extraction")
                .with(request -> {
                    request.setRemoteAddr(ip);
                    return request;
                });
    }

    @Test
    @DisplayName("하루 상한을 넘으면 429 이다 — 10분 한도가 남아 있어도. 성공·실패를 가리지 않고 센다")
    void rejectsOverDailyLimit() throws Exception {
        for (int i = 0; i < 2; i++) {
            mockMvc.perform(read("203.0.113.80", i)).andExpect(status().isOk());
        }

        String retryAfter = mockMvc.perform(read("203.0.113.80", 9))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.type").value(ErrorCode.TOO_MANY_REQUESTS.getType().toString()))
                .andReturn().getResponse().getHeader(HttpHeaders.RETRY_AFTER);

        assertThat(Long.parseLong(retryAfter)).isBetween(601L, 86_400L);   // 10분 한도가 아니라 하루 한도에 걸렸다
    }

    @Test
    @DisplayName("IP 가 다르면 하루 수도 따로 센다")
    void countsPerIp() throws Exception {
        for (int i = 0; i < 2; i++) {
            mockMvc.perform(read("203.0.113.90", i));
        }
        mockMvc.perform(read("203.0.113.90", 5)).andExpect(status().isTooManyRequests());

        mockMvc.perform(read("198.51.100.90", 6)).andExpect(status().isOk());
    }
}
