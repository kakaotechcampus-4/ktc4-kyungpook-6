package com.ktc4.backend.domain.auth.controller;

import com.ktc4.backend.domain.auth.dto.LoginResponse;
import com.ktc4.backend.domain.auth.service.AuthService;
import com.ktc4.backend.domain.member.enums.MemberRole;
import com.ktc4.backend.global.error.CustomException;
import com.ktc4.backend.global.error.ErrorCode;
import com.ktc4.backend.global.error.GlobalExceptionHandler;
import com.ktc4.backend.global.security.JwtProvider;
import com.ktc4.backend.global.security.SecurityConfig;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 로그인 API 의 HTTP 계약 확인. 앱이 이 모양으로 화면을 만들기 때문에 여기서 고정한다.
 */
@WebMvcTest(AuthController.class)
@Import(SecurityConfig.class)
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtProvider jwtProvider;

    @MockitoBean
    private AuthService authService;

    private static String loginBody(String email, String password) {
        return "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}";
    }

    @Test
    @DisplayName("로그인 성공 응답은 토큰·종류·만료 시각(UTC)·역할을 담는다")
    void loginResponseShape() throws Exception {
        when(authService.login(any())).thenReturn(new LoginResponse(
                "issued-token", "Bearer", Instant.parse("2026-10-05T03:00:00Z"), MemberRole.OWNER));

        mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("owner@example.com", "password1234")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("issued-token"))
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresAt").value("2026-10-05T03:00:00Z"))
                .andExpect(jsonPath("$.role").value("OWNER"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "not-an-email"})
    @DisplayName("이메일이 비었거나 형식이 틀리면 400 이고 errors 에 필드를 알려준다")
    void rejectsInvalidEmail(String email) throws Exception {
        mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(email, "password1234")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("email"));
    }

    @Test
    @DisplayName("비밀번호가 비었으면 400")
    void rejectsBlankPassword() throws Exception {
        mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("owner@example.com", "")))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("긴 비밀번호도 요청 검증에서 막지 않는다 — 막으면 거절된 값(비밀번호)이 에러 로그에 찍힌다")
    void longPasswordIsNeverLogged() throws Exception {
        String password = "never-log-this-password-" + "x".repeat(80);
        Logger handlerLogger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        handlerLogger.addAppender(logs);
        try {
            mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                    .content(loginBody("owner@example.com", password)));
        } finally {
            handlerLogger.detachAppender(logs);
        }

        assertThat(logs.list).noneMatch(event -> event.getFormattedMessage().contains("never-log-this-password"));
    }

    @Test
    @DisplayName("이메일·비밀번호가 틀리면 401 invalid-credentials")
    void mapsInvalidCredentials() throws Exception {
        when(authService.login(any())).thenThrow(new CustomException(ErrorCode.INVALID_CREDENTIALS));

        mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("owner@example.com", "wrong-password")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type").value(ErrorCode.INVALID_CREDENTIALS.getType().toString()));
    }

    @Test
    @DisplayName("승인 대기 점주는 403 owner-pending-approval — 앱이 안내 문구를 고를 수 있게")
    void mapsPendingApproval() throws Exception {
        when(authService.login(any())).thenThrow(new CustomException(ErrorCode.OWNER_PENDING_APPROVAL));

        mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("owner@example.com", "password1234")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value(ErrorCode.OWNER_PENDING_APPROVAL.getType().toString()));
    }

    @Test
    @DisplayName("내 정보 조회는 토큰에 담긴 회원 ID 로 조회한다")
    void meUsesTokenMemberId() throws Exception {
        String token = jwtProvider.issue(7L, MemberRole.OWNER).value();

        mockMvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());

        verify(authService).getMe(7L);
    }
}
