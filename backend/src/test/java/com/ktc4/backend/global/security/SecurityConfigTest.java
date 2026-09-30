package com.ktc4.backend.global.security;

import com.ktc4.backend.TestController;
import com.ktc4.backend.domain.auth.controller.AuthController;
import com.ktc4.backend.domain.auth.service.AuthService;
import com.ktc4.backend.domain.checkin.controller.CheckInController;
import com.ktc4.backend.domain.checkin.service.CheckInService;
import com.ktc4.backend.domain.member.controller.OwnerAdminController;
import com.ktc4.backend.domain.member.enums.MemberRole;
import com.ktc4.backend.domain.member.service.OwnerApprovalService;
import com.ktc4.backend.domain.qr.controller.QrCredentialController;
import com.ktc4.backend.domain.qr.service.QrCredentialService;
import com.ktc4.backend.domain.store.controller.StoreController;
import com.ktc4.backend.domain.store.service.StoreService;
import com.ktc4.backend.global.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Clock;
import java.time.Duration;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * API 별 접근 권한 규칙 확인.
 *
 * <p>서비스는 가짜로 두고, 요청이 컨트롤러까지 닿는지(200) 아니면 보안 필터에서 막히는지(401·403)만 본다.
 * 토큰은 이 컨텍스트의 실제 {@link JwtProvider} 로 발급한다.
 */
@WebMvcTest({StoreController.class, AuthController.class, TestController.class, OwnerAdminController.class,
        CheckInController.class, QrCredentialController.class})
@Import(SecurityConfig.class)
@TestPropertySource(properties = {"auth.enforce=true", "auth.internal-api-key=" + SecurityConfigTest.API_KEY})
@DisplayName("API 접근 권한")
class SecurityConfigTest {

    static final String API_KEY = "test-only-internal-api-key";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtProvider jwtProvider;

    @MockitoBean
    private StoreService storeService;

    @MockitoBean
    private AuthService authService;

    @MockitoBean
    private OwnerApprovalService ownerApprovalService;

    @MockitoBean
    private CheckInService checkInService;

    @MockitoBean
    private QrCredentialService qrCredentialService;

    private String bearer(MemberRole role) {
        return "Bearer " + jwtProvider.issue(1L, role).value();
    }

    // 보안 필터의 거절 응답도 GlobalExceptionHandler 와 같은 RFC 9457 모양이어야 한다.
    private static void expectProblem(ResultActions result, ErrorCode errorCode) throws Exception {
        result.andExpect(status().is(errorCode.getHttpStatus().value()))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value(errorCode.getType().toString()))
                .andExpect(jsonPath("$.title").value(errorCode.getTitle()))
                .andExpect(jsonPath("$.status").value(errorCode.getHttpStatus().value()))
                .andExpect(jsonPath("$.detail").doesNotExist());
    }

    @Nested
    @DisplayName("토큰 없이")
    class Anonymous {

        @Test
        @DisplayName("관리자 API 는 401")
        void blocksAdminApi() throws Exception {
            expectProblem(mockMvc.perform(get("/api/stores")), ErrorCode.UNAUTHORIZED);
        }

        @Test
        @DisplayName("로그인 API 는 부를 수 있다")
        void allowsLogin() throws Exception {
            mockMvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"owner@example.com\",\"password\":\"password1234\"}"))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("점주 가입 신청은 부를 수 있다")
        void allowsOwnerSignup() throws Exception {
            mockMvc.perform(post("/api/auth/owners/signup").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"owner@example.com\",\"password\":\"password1234\","
                                    + "\"bizNo\":\"1234567890\",\"storeName\":\"예시분식\",\"representativeName\":\"홍길동\"}"))
                    .andExpect(status().isCreated());
        }

        @Test
        @DisplayName("점주 승인 API 는 401")
        void blocksOwnerAdminApi() throws Exception {
            expectProblem(mockMvc.perform(get("/api/admin/owners")), ErrorCode.UNAUTHORIZED);
            expectProblem(mockMvc.perform(post("/api/admin/owners/3/approve")), ErrorCode.UNAUTHORIZED);
        }

        @Test
        @DisplayName("서버 상태 확인(/ping)은 부를 수 있다")
        void allowsPing() throws Exception {
            mockMvc.perform(get("/ping")).andExpect(status().isOk());
        }

        @Test
        @DisplayName("테스트용 API 는 401")
        void blocksTestApi() throws Exception {
            expectProblem(mockMvc.perform(get("/test/page").param("page", "0").param("limit", "1")),
                    ErrorCode.UNAUTHORIZED);
        }
    }

    @Nested
    @DisplayName("관리자 토큰")
    class Admin {

        @Test
        @DisplayName("가게 조회·수정과 대조 자료를 모두 부를 수 있다")
        void allowsAdminApis() throws Exception {
            mockMvc.perform(get("/api/stores").header(HttpHeaders.AUTHORIZATION, bearer(MemberRole.ADMIN)))
                    .andExpect(status().isOk());
            mockMvc.perform(get("/api/stores/nts-checks").header(HttpHeaders.AUTHORIZATION, bearer(MemberRole.ADMIN)))
                    .andExpect(status().isOk());
            mockMvc.perform(patch("/api/stores/1").header(HttpHeaders.AUTHORIZATION, bearer(MemberRole.ADMIN))
                            .contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("내 정보 조회를 부를 수 있다")
        void allowsMe() throws Exception {
            mockMvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, bearer(MemberRole.ADMIN)))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("점주 가입 신청 목록과 승인을 부를 수 있다")
        void allowsOwnerAdminApi() throws Exception {
            mockMvc.perform(get("/api/admin/owners").header(HttpHeaders.AUTHORIZATION, bearer(MemberRole.ADMIN)))
                    .andExpect(status().isOk());
            mockMvc.perform(post("/api/admin/owners/3/approve").header(HttpHeaders.AUTHORIZATION, bearer(MemberRole.ADMIN)))
                    .andExpect(status().isOk());
        }
    }

    @Nested
    @DisplayName("점주 토큰")
    class Owner {

        @Test
        @DisplayName("관리자 API 는 403")
        void blocksAdminApis() throws Exception {
            expectProblem(mockMvc.perform(get("/api/stores")
                    .header(HttpHeaders.AUTHORIZATION, bearer(MemberRole.OWNER))), ErrorCode.FORBIDDEN);
            expectProblem(mockMvc.perform(patch("/api/stores/1")
                    .header(HttpHeaders.AUTHORIZATION, bearer(MemberRole.OWNER))
                    .contentType(MediaType.APPLICATION_JSON).content("{}")), ErrorCode.FORBIDDEN);
            expectProblem(mockMvc.perform(get("/api/stores/nts-checks")
                    .header(HttpHeaders.AUTHORIZATION, bearer(MemberRole.OWNER))), ErrorCode.FORBIDDEN);
        }

        @Test
        @DisplayName("점주가 점주 승인 API 를 부르면 403 — 스스로 승인할 수 없게")
        void cannotApproveSelf() throws Exception {
            expectProblem(mockMvc.perform(post("/api/admin/owners/1/approve")
                    .header(HttpHeaders.AUTHORIZATION, bearer(MemberRole.OWNER))), ErrorCode.FORBIDDEN);
        }

        @Test
        @DisplayName("권한 목록에 없는 경로도 403 — 새 API 가 설정 누락으로 열리지 않게")
        void blocksUnlistedPath() throws Exception {
            expectProblem(mockMvc.perform(get("/api/anything-new")
                    .header(HttpHeaders.AUTHORIZATION, bearer(MemberRole.OWNER))), ErrorCode.FORBIDDEN);
        }

        @Test
        @DisplayName("내 정보 조회는 부를 수 있다")
        void allowsMe() throws Exception {
            mockMvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, bearer(MemberRole.OWNER)))
                    .andExpect(status().isOk());
        }
    }

    @Nested
    @DisplayName("잘못된 토큰")
    class InvalidToken {

        @Test
        @DisplayName("형식이 틀린 토큰은 401")
        void rejectsMalformed() throws Exception {
            expectProblem(mockMvc.perform(get("/api/stores")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer not.a.token")), ErrorCode.UNAUTHORIZED);
        }

        @Test
        @DisplayName("다른 키로 서명한 토큰은 401")
        void rejectsForeignSignature() throws Exception {
            JwtProvider other = new JwtProvider("another-test-only-secret-at-least-32-bytes",
                    Duration.ofDays(7), Clock.systemUTC());
            String foreign = "Bearer " + other.issue(1L, MemberRole.ADMIN).value();

            expectProblem(mockMvc.perform(get("/api/stores").header(HttpHeaders.AUTHORIZATION, foreign)),
                    ErrorCode.UNAUTHORIZED);
        }

        @Test
        @DisplayName("Bearer 없이 토큰만 보내면 401")
        void rejectsWithoutBearerPrefix() throws Exception {
            String raw = jwtProvider.issue(1L, MemberRole.ADMIN).value();

            expectProblem(mockMvc.perform(get("/api/stores").header(HttpHeaders.AUTHORIZATION, raw)),
                    ErrorCode.UNAUTHORIZED);
        }
    }

    @Nested
    @DisplayName("서버 간 API 키")
    class ApiKey {

        @Test
        @DisplayName("맞는 키로 국세청 대조 자료를 부를 수 있다")
        void allowsNtsChecks() throws Exception {
            mockMvc.perform(get("/api/stores/nts-checks").header(ApiKeyAuthenticationFilter.HEADER, API_KEY))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("맞는 키여도 다른 관리자 API 는 403 — 키가 새도 관리자 권한까지 열리지 않게")
        void blocksOtherApis() throws Exception {
            expectProblem(mockMvc.perform(get("/api/stores")
                    .header(ApiKeyAuthenticationFilter.HEADER, API_KEY)), ErrorCode.FORBIDDEN);
        }

        @Test
        @DisplayName("틀린 키나 빈 키는 401")
        void rejectsWrongKey() throws Exception {
            expectProblem(mockMvc.perform(get("/api/stores/nts-checks")
                    .header(ApiKeyAuthenticationFilter.HEADER, "wrong-key")), ErrorCode.UNAUTHORIZED);
            expectProblem(mockMvc.perform(get("/api/stores/nts-checks")
                    .header(ApiKeyAuthenticationFilter.HEADER, "")), ErrorCode.UNAUTHORIZED);
        }
    }

    @Nested
    @DisplayName("아동 QR 체크인·발급 (PR #50)")
    class CheckInAndQr {

        private static final String CHECK_IN_BODY = "{\"qrPayload\":\"v1.qr-for-security-test\"}";

        @Test
        @DisplayName("점주·관리자는 체크인할 수 있다 — /api/stores/** 관리자 규칙보다 먼저 적용")
        void ownerAndAdminCanCheckIn() throws Exception {
            mockMvc.perform(post("/api/stores/1/check-ins").header(HttpHeaders.AUTHORIZATION, bearer(MemberRole.OWNER))
                            .contentType(MediaType.APPLICATION_JSON).content(CHECK_IN_BODY))
                    .andExpect(status().isCreated());
            mockMvc.perform(post("/api/stores/1/check-ins").header(HttpHeaders.AUTHORIZATION, bearer(MemberRole.ADMIN))
                            .contentType(MediaType.APPLICATION_JSON).content(CHECK_IN_BODY))
                    .andExpect(status().isCreated());
        }

        @Test
        @DisplayName("토큰 없이 체크인하면 401")
        void anonymousCannotCheckIn() throws Exception {
            expectProblem(mockMvc.perform(post("/api/stores/1/check-ins")
                    .contentType(MediaType.APPLICATION_JSON).content(CHECK_IN_BODY)), ErrorCode.UNAUTHORIZED);
        }

        @Test
        @DisplayName("체크인 규칙은 체크인 경로만 연다 — 점주가 다른 가게 API 를 부르면 여전히 403")
        void checkInRuleDoesNotOpenOtherStoreApis() throws Exception {
            expectProblem(mockMvc.perform(patch("/api/stores/1")
                    .header(HttpHeaders.AUTHORIZATION, bearer(MemberRole.OWNER))
                    .contentType(MediaType.APPLICATION_JSON).content("{}")), ErrorCode.FORBIDDEN);
        }

        @Test
        @DisplayName("QR 발급은 관리자만 — 아동 인증 전까지 남의 QR 을 재발급(무효화)할 수 없게")
        void qrIssueIsAdminOnly() throws Exception {
            expectProblem(mockMvc.perform(post("/api/children/7/qr-token")
                    .header(HttpHeaders.AUTHORIZATION, bearer(MemberRole.OWNER))), ErrorCode.FORBIDDEN);
            expectProblem(mockMvc.perform(post("/api/children/7/qr-token")), ErrorCode.UNAUTHORIZED);
            mockMvc.perform(post("/api/children/7/qr-token").header(HttpHeaders.AUTHORIZATION, bearer(MemberRole.ADMIN)))
                    .andExpect(status().isOk());
        }
    }
}
