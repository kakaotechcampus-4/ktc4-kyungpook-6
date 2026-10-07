package com.ktc4.backend.domain.auth.controller;

import com.ktc4.backend.domain.auth.dto.LoginResponse;
import com.ktc4.backend.domain.auth.dto.MemberResponse;
import com.ktc4.backend.domain.member.enums.MemberStatus;
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

    private static final String PHONE = "010-0000-0000";

    private static String signupBody(String email, String password, String bizNo,
                                     String storeName, String representativeName) {
        return signupBody(email, password, bizNo, storeName, representativeName, PHONE);
    }

    private static String signupBody(String email, String password, String bizNo,
                                     String storeName, String representativeName, String phone) {
        return "{\"email\":\"" + email + "\",\"password\":\"" + password + "\",\"bizNo\":\"" + bizNo
                + "\",\"storeName\":\"" + storeName + "\",\"representativeName\":\"" + representativeName
                + "\",\"phone\":\"" + phone + "\"}";
    }

    @Test
    @DisplayName("휴대폰 번호가 없거나 비었으면 400 이고 errors 에 필드를 알려준다")
    void rejectsMissingPhone() throws Exception {
        mockMvc.perform(post("/api/auth/owners/signup").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"owner@example.com\",\"password\":\"password1234\","
                                + "\"bizNo\":\"1234567890\",\"storeName\":\"예시분식\",\"representativeName\":\"홍길동\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("phone"));
        mockMvc.perform(post("/api/auth/owners/signup").contentType(MediaType.APPLICATION_JSON)
                        .content(signupBody("owner@example.com", "password1234", "1234567890", "예시분식", "홍길동", " ")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("phone"));
    }

    @Test
    @DisplayName("휴대폰 번호 형식이 아니면 400 invalid-phone")
    void mapsInvalidPhone() throws Exception {
        when(authService.signupOwner(any())).thenThrow(new CustomException(ErrorCode.INVALID_PHONE));

        mockMvc.perform(post("/api/auth/owners/signup").contentType(MediaType.APPLICATION_JSON)
                        .content(signupBody("owner@example.com", "password1234", "1234567890", "예시분식", "홍길동",
                                "053-000-0000")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value(ErrorCode.INVALID_PHONE.getType().toString()));
    }

    @Test
    @DisplayName("너무 긴 휴대폰 번호는 400 이고, 보낸 값이 로그에도 응답에도 남지 않는다")
    void rejectsTooLongPhoneWithoutEchoingIt() throws Exception {
        String tooLong = "010-0000-0000-9999-9999";
        Logger handlerLogger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        handlerLogger.addAppender(logs);
        String responseBody;
        try {
            responseBody = mockMvc.perform(post("/api/auth/owners/signup").contentType(MediaType.APPLICATION_JSON)
                            .content(signupBody("owner@example.com", "password1234", "1234567890", "예시분식", "홍길동",
                                    tooLong)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[0].field").value("phone"))
                    .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        } finally {
            handlerLogger.detachAppender(logs);
        }

        assertThat(logs.list).noneMatch(event -> event.getFormattedMessage().contains(tooLong));
        // 응답의 errors 는 어느 칸이 왜 틀렸는지만 알려 준다. 거절된 값을 되돌려 주지 않는다.
        assertThat(responseBody).doesNotContain(tooLong).doesNotContain("9999");
    }

    @Test
    @DisplayName("가입 신청은 201 과 승인 대기 상태를 돌려준다")
    void signupReturnsCreatedPending() throws Exception {
        when(authService.signupOwner(any())).thenReturn(
                new MemberResponse(3L, "owner@example.com", MemberRole.OWNER, MemberStatus.PENDING));

        mockMvc.perform(post("/api/auth/owners/signup").contentType(MediaType.APPLICATION_JSON)
                        .content(signupBody("owner@example.com", "password1234", "123-45-67890", "예시분식", "홍길동")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.memberId").value(3))
                .andExpect(jsonPath("$.role").value("OWNER"))
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    @DisplayName("비밀번호가 8자보다 짧으면 400 이고, 비밀번호·대표자 이름 모두 로그에 남지 않는다")
    void rejectsShortPasswordWithoutLoggingIt() throws Exception {
        Logger handlerLogger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        handlerLogger.addAppender(logs);
        try {
            mockMvc.perform(post("/api/auth/owners/signup").contentType(MediaType.APPLICATION_JSON)
                            .content(signupBody("owner@example.com", "short7!", "1234567890", "예시분식", "비공개이름")))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[0].field").value("password"));
        } finally {
            handlerLogger.detachAppender(logs);
        }

        assertThat(logs.list).noneMatch(event -> event.getFormattedMessage().contains("short7!")
                || event.getFormattedMessage().contains("비공개이름"));
    }

    @Test
    @DisplayName("전각 공백(U+3000)만 넣은 상호명·대표자 이름은 400 — 저장 뒤 빈 값이 되지 않게")
    void rejectsFullWidthSpaceOnly() throws Exception {
        // JSON 의 \u3000 이스케이프로 보낸다 — 소스에 보이지 않는 글자를 직접 쓰지 않기 위해서다.
        mockMvc.perform(post("/api/auth/owners/signup").contentType(MediaType.APPLICATION_JSON)
                        .content(signupBody("owner@example.com", "password1234", "1234567890", "\\u3000", "홍길동")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("storeName"));
        mockMvc.perform(post("/api/auth/owners/signup").contentType(MediaType.APPLICATION_JSON)
                        .content(signupBody("owner@example.com", "password1234", "1234567890", "예시분식", "\\u3000\\u3000")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("representativeName"));
    }

    // 소스에 보이지 않는 글자를 직접 쓰지 않으려고 JSON 이스케이프(\\uXXXX)로 보낸다.
    @ParameterizedTest
    @ValueSource(strings = {
            "\\u200B",            // 폭 없는 공백 (Cf)
            "\\uFEFF",            // BOM (Cf)
            "\\u2060",            // 단어 연결자 (Cf)
            "\\u202E예시분식",     // 글자 방향 뒤집기 (Cf) — 이름이 뒤집혀 보이게 할 수 있음
            "예시\\u200D분식",     // 이모지 결합 글자 ZWJ (Cf)
            "\\u3164",            // 한글 채움 문자 (Lo — 글자로 분류되지만 보이지 않음)
            "\\uFFA0",            // 반각 한글 채움 문자 (Lo)
            "\\u2800",            // 점자 빈칸 (So)
            "예시\\u034F분식",     // 결합 글자 연결자 (Mn — 보이지 않음)
            "\\uE000예시분식",     // 사용자 정의 영역 (Co)
            "예시\\u0378분식",     // 배정되지 않은 코드 (Cn)
            "예\\u0301\\u0301\\u0301시", // 결합 부호 3개 연속 — 겹겹이 쌓아 화면을 망가뜨리는 문자열
            "!!!"                  // 글자·숫자 없이 기호만
    })
    @DisplayName("보이지 않거나 글자·숫자가 없는 상호명은 400")
    void rejectsInvisibleOrLetterlessStoreName(String storeName) throws Exception {
        mockMvc.perform(post("/api/auth/owners/signup").contentType(MediaType.APPLICATION_JSON)
                        .content(signupBody("owner@example.com", "password1234", "1234567890", storeName, "홍길동")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("storeName"));
    }

    @Test
    @DisplayName("대표자 이름에도 같은 규칙 — 한글 채움 문자만 넣으면 400")
    void rejectsInvisibleRepresentativeName() throws Exception {
        mockMvc.perform(post("/api/auth/owners/signup").contentType(MediaType.APPLICATION_JSON)
                        .content(signupBody("owner@example.com", "password1234", "1234567890", "예시분식", "\\u3164\\u3164")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("representativeName"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"예시 분식", "예시분식 2호점", "예시분식 🍜", "EXAMPLE 식당", "24시 분식",
            "예시분식 \\u2764\\uFE0F", "Cafe\\u0301 예시"})   // ❤️(부호 1개), é(부호 1개)는 통과
    @DisplayName("공백·숫자·영문·단일 이모지가 섞인 평범한 상호명은 통과한다 — 규칙이 너무 좁지 않은지")
    void acceptsOrdinaryStoreNames(String storeName) throws Exception {
        mockMvc.perform(post("/api/auth/owners/signup").contentType(MediaType.APPLICATION_JSON)
                        .content(signupBody("owner@example.com", "password1234", "1234567890", storeName, "홍길동")))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("줄바꿈 같은 제어 문자가 들어간 상호명·대표자 이름은 400")
    void rejectsControlCharacters() throws Exception {
        mockMvc.perform(post("/api/auth/owners/signup").contentType(MediaType.APPLICATION_JSON)
                        .content(signupBody("owner@example.com", "password1234", "1234567890", "예시\\n분식", "홍길동")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("storeName"));
        mockMvc.perform(post("/api/auth/owners/signup").contentType(MediaType.APPLICATION_JSON)
                        .content(signupBody("owner@example.com", "password1234", "1234567890", "예시분식", "홍\\t길동")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("representativeName"));
    }

    @Test
    @DisplayName("상호명·대표자 이름이 비었으면 400")
    void rejectsBlankOwnerInfo() throws Exception {
        mockMvc.perform(post("/api/auth/owners/signup").contentType(MediaType.APPLICATION_JSON)
                        .content(signupBody("owner@example.com", "password1234", "1234567890", " ", "")))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("이미 가입된 이메일이면 409 duplicate-email")
    void mapsDuplicateEmail() throws Exception {
        when(authService.signupOwner(any())).thenThrow(new CustomException(ErrorCode.DUPLICATE_EMAIL));

        mockMvc.perform(post("/api/auth/owners/signup").contentType(MediaType.APPLICATION_JSON)
                        .content(signupBody("owner@example.com", "password1234", "1234567890", "예시분식", "홍길동")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value(ErrorCode.DUPLICATE_EMAIL.getType().toString()));
    }
}
