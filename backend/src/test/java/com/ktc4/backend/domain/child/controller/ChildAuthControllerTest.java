package com.ktc4.backend.domain.child.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ktc4.backend.domain.child.dto.ChildAuthResponse;
import com.ktc4.backend.domain.child.service.ChildAuthService;
import com.ktc4.backend.global.error.CustomException;
import com.ktc4.backend.global.error.ErrorCode;
import com.ktc4.backend.global.security.SecurityConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 아동 카드 인증 API 의 HTTP 계약. 실제 보안 규칙으로 돌려 토큰 없이 부를 수 있는지까지 본다.
 * 아동을 찾고 만드는 일은 {@code ChildAuthServiceTest} 몫이다.
 */
@WebMvcTest(ChildAuthController.class)
@Import(SecurityConfig.class)
@DisplayName("ChildAuthController")
class ChildAuthControllerTest {

    private static final String URL = "/api/auth/children/card";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ChildAuthService childAuthService;

    private ResultActions authenticate(String body) throws Exception {
        return mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @Test
    @DisplayName("토큰 없이 부르고, 200 응답 칸은 accessToken·tokenType·expiresAt 셋뿐이다 — 아동 번호를 내보내지 않는다")
    void returnsTokenOnly() throws Exception {
        when(childAuthService.authenticate("1234 5678 1234 5678")).thenReturn(
                new ChildAuthResponse("child-token", "Bearer", Instant.parse("2026-11-10T03:00:00Z")));

        String body = authenticate("{\"cardNumber\":\"1234 5678 1234 5678\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("child-token"))
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresAt").value("2026-11-10T03:00:00Z"))
                .andReturn().getResponse().getContentAsString();

        JsonNode json = new ObjectMapper().readTree(body);
        assertThat(json.fieldNames()).toIterable().containsExactlyInAnyOrder("accessToken", "tokenType", "expiresAt");
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {"1234567812345678", "1234 5678 1234 5678", "1234-5678-1234-5678"})
    @DisplayName("숫자 16자리는 붙여 쓰든 띄어 쓰든 받는다")
    void acceptsSixteenDigits(String card) throws Exception {
        when(childAuthService.authenticate(card)).thenReturn(new ChildAuthResponse("t", "Bearer", Instant.EPOCH));

        authenticate("{\"cardNumber\":\"" + card + "\"}").andExpect(status().isOk());
    }

    @ParameterizedTest(name = "[{index}] \"{0}\"")
    @ValueSource(strings = {"", "123456781234567", "12345678123456789", "1234 5678 1234 567a", "1234  5678 1234 5678",
            " 1234567812345678", "1234567812345678 ", "-1234567812345678", "１２３４５６７８１２３４５６７８"})
    @DisplayName("숫자 16자리가 아니면 서비스를 부르지 않고 400 invalid-request, errors 에 cardNumber 칸")
    void rejectsMalformed(String card) throws Exception {
        authenticate("{\"cardNumber\":\"" + card + "\"}")
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value(ErrorCode.INVALID_REQUEST.getType().toString()))
                .andExpect(jsonPath("$.errors[0].field").value("cardNumber"));

        verify(childAuthService, never()).authenticate(anyString());
    }

    @Test
    @DisplayName("카드 번호가 없으면 400 invalid-request, errors 에 cardNumber 칸")
    void rejectsMissing() throws Exception {
        authenticate("{}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("cardNumber"));

        verify(childAuthService, never()).authenticate(anyString());
    }

    @Test
    @DisplayName("해시 키가 없어 인증할 수 없으면 503 card-auth-unavailable")
    void unavailable() throws Exception {
        when(childAuthService.authenticate(anyString())).thenThrow(new CustomException(ErrorCode.CARD_AUTH_UNAVAILABLE));

        authenticate("{\"cardNumber\":\"1234567812345678\"}")
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.type").value(ErrorCode.CARD_AUTH_UNAVAILABLE.getType().toString()));
    }
}
