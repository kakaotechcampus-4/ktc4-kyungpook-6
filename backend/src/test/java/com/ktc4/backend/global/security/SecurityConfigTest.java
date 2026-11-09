package com.ktc4.backend.global.security;

import com.ktc4.backend.TestController;
import com.ktc4.backend.domain.auth.controller.AuthController;
import com.ktc4.backend.domain.auth.service.AuthService;
import com.ktc4.backend.domain.checkin.controller.CheckInController;
import com.ktc4.backend.domain.checkin.service.CheckInService;
import com.ktc4.backend.domain.child.controller.ChildAuthController;
import com.ktc4.backend.domain.child.service.ChildAuthService;
import com.ktc4.backend.domain.member.controller.OwnerAdminController;
import com.ktc4.backend.domain.member.enums.MemberRole;
import com.ktc4.backend.domain.member.service.OwnerApprovalService;
import com.ktc4.backend.domain.order.controller.ChildOrderController;
import com.ktc4.backend.domain.order.controller.OrderAdminController;
import com.ktc4.backend.domain.order.controller.StoreOrderController;
import com.ktc4.backend.domain.order.service.OrderService;
import com.ktc4.backend.domain.qr.controller.QrCredentialController;
import com.ktc4.backend.domain.qr.service.QrCredentialService;
import com.ktc4.backend.domain.store.controller.StoreController;
import com.ktc4.backend.domain.store.service.StoreManagementService;
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
import java.util.List;

import static com.ktc4.backend.support.SignupMultipart.signupRequest;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
        CheckInController.class, QrCredentialController.class, ChildAuthController.class, ChildOrderController.class,
        StoreOrderController.class, OrderAdminController.class})
@Import(SecurityConfig.class)
@TestPropertySource(properties = {"auth.internal-api-key=" + SecurityConfigTest.API_KEY})
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
    private StoreManagementService storeManagementService;

    @MockitoBean
    private AuthService authService;

    @MockitoBean
    private OwnerApprovalService ownerApprovalService;

    @MockitoBean
    private CheckInService checkInService;

    @MockitoBean
    private QrCredentialService qrCredentialService;

    @MockitoBean
    private ChildAuthService childAuthService;

    @MockitoBean
    private OrderService orderService;

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
        @DisplayName("관리자 API 는 401 — 가게 목록·국세청 대조 자료·가게 수정")
        void blocksAdminApi() throws Exception {
            expectProblem(mockMvc.perform(get("/api/stores")), ErrorCode.UNAUTHORIZED);
            expectProblem(mockMvc.perform(get("/api/stores/nts-checks")), ErrorCode.UNAUTHORIZED);
            expectProblem(mockMvc.perform(patch("/api/stores/1")
                    .contentType(MediaType.APPLICATION_JSON).content("{}")), ErrorCode.UNAUTHORIZED);

            verifyNoInteractions(storeService);
        }

        @Test
        @DisplayName("내 정보 조회도 토큰이 있어야 한다 — 토큰 주인을 알려주는 API 다")
        void meRequiresToken() throws Exception {
            expectProblem(mockMvc.perform(get("/api/auth/me")), ErrorCode.UNAUTHORIZED);

            verifyNoInteractions(authService);
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
        @DisplayName("아동 카드 인증은 부를 수 있다")
        void allowsChildCardAuth() throws Exception {
            mockMvc.perform(post("/api/auth/children/card")
                            .contentType(MediaType.APPLICATION_JSON).content("{\"cardNumber\":\"1234567812345678\"}"))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("점주 가입 신청은 부를 수 있다")
        void allowsOwnerSignup() throws Exception {
            mockMvc.perform(signupRequest("{\"email\":\"owner@example.com\",\"password\":\"password1234\","
                                    + "\"bizNo\":\"1234567890\",\"storeName\":\"예시분식\",\"representativeName\":\"홍길동\","
                                    + "\"phone\":\"010-0000-0000\",\"storeAddress\":\"대구광역시 북구 대학로 80\","
                                    + "\"storePhone\":\"053-123-4567\","
                                    + "\"businessHours\":[{\"day\":\"MON\",\"open\":\"11:00\",\"close\":\"21:00\"}],"
                                    + "\"menus\":[{\"name\":\"김치찌개\"}],"
                                    + "\"bizCertFileId\":\"7b1d0e4f-2c3a-4d5b-8e6f-9a0b1c2d3e4f\"}"))
                    .andExpect(status().isCreated());
        }

        @Test
        @DisplayName("점주 승인 API 는 401")
        void blocksOwnerAdminApi() throws Exception {
            expectProblem(mockMvc.perform(get("/api/admin/owners")), ErrorCode.UNAUTHORIZED);
            expectProblem(mockMvc.perform(get("/api/admin/owners/3/store-candidates")), ErrorCode.UNAUTHORIZED);
            expectProblem(mockMvc.perform(post("/api/admin/owners/3/approve")
                    .contentType(MediaType.APPLICATION_JSON).content("{\"storeId\":10}")), ErrorCode.UNAUTHORIZED);

            // 승인은 "누가 인정했는지"를 남기므로 토큰 없는 요청은 서비스까지 닿지도 않아야 한다
            verifyNoInteractions(ownerApprovalService);
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
        @DisplayName("점주 가입 신청 목록, 후보 가게 조회, 승인을 부를 수 있다")
        void allowsOwnerAdminApi() throws Exception {
            mockMvc.perform(get("/api/admin/owners").header(HttpHeaders.AUTHORIZATION, bearer(MemberRole.ADMIN)))
                    .andExpect(status().isOk());
            mockMvc.perform(get("/api/admin/owners/3/store-candidates")
                            .header(HttpHeaders.AUTHORIZATION, bearer(MemberRole.ADMIN)))
                    .andExpect(status().isOk());
            mockMvc.perform(post("/api/admin/owners/3/approve").header(HttpHeaders.AUTHORIZATION, bearer(MemberRole.ADMIN))
                            .contentType(MediaType.APPLICATION_JSON).content("{\"storeId\":10}"))
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
                    .header(HttpHeaders.AUTHORIZATION, bearer(MemberRole.OWNER))
                    .contentType(MediaType.APPLICATION_JSON).content("{\"storeId\":10}")), ErrorCode.FORBIDDEN);
            expectProblem(mockMvc.perform(get("/api/admin/owners/1/store-candidates")
                    .header(HttpHeaders.AUTHORIZATION, bearer(MemberRole.OWNER))), ErrorCode.FORBIDDEN);

            verifyNoInteractions(ownerApprovalService);
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
    @DisplayName("가입 상태 확인용 토큰 — 승인 전 신청자")
    class SignupStatus {

        private String statusBearer() {
            return "Bearer " + jwtProvider.issueSignupStatus(1L).value();
        }

        @Test
        @DisplayName("내 정보 조회는 부를 수 있다 — 승인 여부를 여기서 본다")
        void allowsMe() throws Exception {
            mockMvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, statusBearer()))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("체크인은 403 — 승인 전에는 점주가 아니다")
        void cannotCheckIn() throws Exception {
            expectProblem(mockMvc.perform(post("/api/stores/1/check-ins")
                    .header(HttpHeaders.AUTHORIZATION, statusBearer())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"qrPayload\":\"v1.qr-for-security-test\"}")), ErrorCode.FORBIDDEN);
        }

        @Test
        @DisplayName("관리자 API 와 점주 승인 API 는 403 — 스스로 승인할 수 없다")
        void blocksAdminApis() throws Exception {
            expectProblem(mockMvc.perform(get("/api/stores")
                    .header(HttpHeaders.AUTHORIZATION, statusBearer())), ErrorCode.FORBIDDEN);
            expectProblem(mockMvc.perform(get("/api/admin/owners")
                    .header(HttpHeaders.AUTHORIZATION, statusBearer())), ErrorCode.FORBIDDEN);
            expectProblem(mockMvc.perform(post("/api/admin/owners/1/approve")
                    .header(HttpHeaders.AUTHORIZATION, statusBearer())
                    .contentType(MediaType.APPLICATION_JSON).content("{\"storeId\":10}")), ErrorCode.FORBIDDEN);
            expectProblem(mockMvc.perform(post("/api/children/me/qr-token")
                    .header(HttpHeaders.AUTHORIZATION, statusBearer())), ErrorCode.FORBIDDEN);

            verifyNoInteractions(storeService, ownerApprovalService, qrCredentialService);
        }

        @Test
        @DisplayName("권한 목록에 없는 경로도 403 — 부를 수 있는 API 는 내 정보 조회 하나다")
        void blocksUnlistedPath() throws Exception {
            expectProblem(mockMvc.perform(get("/api/anything-new")
                    .header(HttpHeaders.AUTHORIZATION, statusBearer())), ErrorCode.FORBIDDEN);
        }
    }

    @Nested
    @DisplayName("아동 토큰 — 급식카드 인증")
    class Child {

        private String childBearer() {
            return "Bearer " + jwtProvider.issueChild(1L).value();
        }

        @Test
        @DisplayName("관리자 API 와 점주 승인 API 는 403")
        void blocksAdminApis() throws Exception {
            expectProblem(mockMvc.perform(get("/api/stores")
                    .header(HttpHeaders.AUTHORIZATION, childBearer())), ErrorCode.FORBIDDEN);
            expectProblem(mockMvc.perform(get("/api/admin/owners")
                    .header(HttpHeaders.AUTHORIZATION, childBearer())), ErrorCode.FORBIDDEN);

            verifyNoInteractions(storeService, ownerApprovalService);
        }

        @Test
        @DisplayName("점주 API(가게 상세·체크인)는 403 — 아동 번호가 회원 번호로 읽혀 남의 가게에 닿지 않게")
        void blocksOwnerApis() throws Exception {
            expectProblem(mockMvc.perform(get("/api/stores/1")
                    .header(HttpHeaders.AUTHORIZATION, childBearer())), ErrorCode.FORBIDDEN);
            expectProblem(mockMvc.perform(post("/api/stores/1/check-ins")
                    .header(HttpHeaders.AUTHORIZATION, childBearer())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"qrPayload\":\"v1.qr-for-security-test\"}")), ErrorCode.FORBIDDEN);

            verifyNoInteractions(storeService, checkInService);
        }

        @Test
        @DisplayName("내 정보 조회는 403 — 회원 정보를 돌려주는 API 다")
        void blocksMe() throws Exception {
            expectProblem(mockMvc.perform(get("/api/auth/me")
                    .header(HttpHeaders.AUTHORIZATION, childBearer())), ErrorCode.FORBIDDEN);

            verifyNoInteractions(authService);
        }

        @Test
        @DisplayName("권한 목록에 없는 경로도 403 — 부를 수 있는 API 는 내 QR·내 주문뿐이다")
        void blocksUnlistedPath() throws Exception {
            expectProblem(mockMvc.perform(get("/api/anything-new")
                    .header(HttpHeaders.AUTHORIZATION, childBearer())), ErrorCode.FORBIDDEN);
        }
    }

    @Nested
    @DisplayName("주문 — 아동·점주·관리자")
    class Orders {

        private static final String CREATE_BODY = "{\"storeId\":1,\"menuId\":3,\"receiveType\":\"TAKEOUT\"}";
        private static final List<String> STORE_ACTIONS = List.of(
                "/api/stores/1/orders/9/start-cooking", "/api/stores/1/orders/9/complete", "/api/stores/1/orders/9/cancel");

        private String childBearer() {
            return "Bearer " + jwtProvider.issueChild(7L).value();
        }

        // 보안 규칙에서 막혀 컨트롤러에 닿지 않았는지(handler 없음)까지 본다
        private void expectBlocked(ResultActions result, ErrorCode errorCode) throws Exception {
            expectProblem(result.andExpect(r -> assertThat(r.getHandler()).isNull()), errorCode);
        }

        @Test
        @DisplayName("아동은 내 주문 만들기·진행 중 보기·취소를 부를 수 있다")
        void childReachesOwnOrders() throws Exception {
            mockMvc.perform(post("/api/children/me/orders").header(HttpHeaders.AUTHORIZATION, childBearer())
                    .contentType(MediaType.APPLICATION_JSON).content(CREATE_BODY)).andExpect(status().isCreated());
            mockMvc.perform(get("/api/children/me/orders/active").header(HttpHeaders.AUTHORIZATION, childBearer()))
                    .andExpect(status().isOk());
            mockMvc.perform(post("/api/children/me/orders/9/cancel").header(HttpHeaders.AUTHORIZATION, childBearer()))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("아동 주문 API 는 점주·관리자 토큰으로 403, 토큰 없이 401")
        void childOrdersAreChildOnly() throws Exception {
            for (MemberRole role : List.of(MemberRole.OWNER, MemberRole.ADMIN)) {
                expectBlocked(mockMvc.perform(post("/api/children/me/orders").header(HttpHeaders.AUTHORIZATION, bearer(role))
                        .contentType(MediaType.APPLICATION_JSON).content(CREATE_BODY)), ErrorCode.FORBIDDEN);
                expectBlocked(mockMvc.perform(get("/api/children/me/orders/active")
                        .header(HttpHeaders.AUTHORIZATION, bearer(role))), ErrorCode.FORBIDDEN);
                expectBlocked(mockMvc.perform(post("/api/children/me/orders/9/cancel")
                        .header(HttpHeaders.AUTHORIZATION, bearer(role))), ErrorCode.FORBIDDEN);
            }
            expectBlocked(mockMvc.perform(get("/api/children/me/orders/active")), ErrorCode.UNAUTHORIZED);

            verifyNoInteractions(orderService);
        }

        @Test
        @DisplayName("점주·관리자는 가게 주문 목록·조리 시작·완료·취소에 닿는다 — 연결된 가게인지는 서비스가 본다")
        void ownerAndAdminReachStoreOrders() throws Exception {
            for (MemberRole role : List.of(MemberRole.OWNER, MemberRole.ADMIN)) {
                mockMvc.perform(get("/api/stores/1/orders").header(HttpHeaders.AUTHORIZATION, bearer(role)))
                        .andExpect(status().isOk());
                for (String action : STORE_ACTIONS) {
                    mockMvc.perform(post(action).header(HttpHeaders.AUTHORIZATION, bearer(role)))
                            .andExpect(status().isOk());
                }
            }
        }

        @Test
        @DisplayName("아동 토큰으로 가게 주문·관리자 주문은 403 — 남의 주문을 처리하거나 볼 수 없다")
        void childCannotHandleStoreOrders() throws Exception {
            expectBlocked(mockMvc.perform(get("/api/stores/1/orders")
                    .header(HttpHeaders.AUTHORIZATION, childBearer())), ErrorCode.FORBIDDEN);
            for (String action : STORE_ACTIONS) {
                expectBlocked(mockMvc.perform(post(action).header(HttpHeaders.AUTHORIZATION, childBearer())),
                        ErrorCode.FORBIDDEN);
            }
            expectBlocked(mockMvc.perform(get("/api/admin/orders")
                    .header(HttpHeaders.AUTHORIZATION, childBearer())), ErrorCode.FORBIDDEN);

            verifyNoInteractions(orderService);
        }

        @Test
        @DisplayName("가게 번호가 숫자가 아닌 주문 경로는 점주에게 열리지 않는다 — 넓은 /api/stores/** 관리자 규칙으로 간다")
        void storeOrderRuleIsNarrow() throws Exception {
            expectBlocked(mockMvc.perform(get("/api/stores/nts-checks/orders")
                    .header(HttpHeaders.AUTHORIZATION, bearer(MemberRole.OWNER))), ErrorCode.FORBIDDEN);
        }

        @Test
        @DisplayName("관리자 주문 내역은 관리자만 — 점주 403, 토큰 없이 401")
        void adminOrdersAreAdminOnly() throws Exception {
            mockMvc.perform(get("/api/admin/orders").header(HttpHeaders.AUTHORIZATION, bearer(MemberRole.ADMIN)))
                    .andExpect(status().isOk());
            expectBlocked(mockMvc.perform(get("/api/admin/orders")
                    .header(HttpHeaders.AUTHORIZATION, bearer(MemberRole.OWNER))), ErrorCode.FORBIDDEN);
            expectBlocked(mockMvc.perform(get("/api/admin/orders")), ErrorCode.UNAUTHORIZED);
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
        @DisplayName("내 QR 발급은 아동 토큰만 — 점주·관리자도 남의 QR 을 재발급(무효화)할 수 없게")
        void myQrIsChildOnly() throws Exception {
            mockMvc.perform(post("/api/children/me/qr-token")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwtProvider.issueChild(7L).value()))
                    .andExpect(status().isOk());
            // 보안 규칙에서 막혀 컨트롤러에 닿지 않는다(handler 없음) — 컨트롤러의 두 번째 방어선이 대신 막아 준 것과 구분한다
            for (MemberRole role : List.of(MemberRole.OWNER, MemberRole.ADMIN)) {
                expectProblem(mockMvc.perform(post("/api/children/me/qr-token")
                                .header(HttpHeaders.AUTHORIZATION, bearer(role)))
                        .andExpect(result -> assertThat(result.getHandler()).as(role.name()).isNull()),
                        ErrorCode.FORBIDDEN);
            }
            expectProblem(mockMvc.perform(post("/api/children/me/qr-token")), ErrorCode.UNAUTHORIZED);
        }

        @Test
        @DisplayName("내 QR 발급은 가입 상태 확인용 토큰·서버 간 API 키로도 안 된다")
        void myQrRejectsOtherCredentials() throws Exception {
            expectProblem(mockMvc.perform(post("/api/children/me/qr-token")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwtProvider.issueSignupStatus(1L).value())),
                    ErrorCode.FORBIDDEN);
            expectProblem(mockMvc.perform(post("/api/children/me/qr-token").header("X-API-KEY", API_KEY)),
                    ErrorCode.FORBIDDEN);

            verifyNoInteractions(qrCredentialService);
        }

        @Test
        @DisplayName("다른 키로 서명한 아동 토큰은 401 — 아동 번호를 지어내 남의 QR 을 재발급할 수 없다")
        void myQrRejectsForgedChildToken() throws Exception {
            JwtProvider foreign = new JwtProvider("another-test-only-secret-at-least-32-bytes",
                    Duration.ofDays(7), Clock.systemUTC());

            expectProblem(mockMvc.perform(post("/api/children/me/qr-token")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + foreign.issueChild(7L).value())),
                    ErrorCode.UNAUTHORIZED);

            verifyNoInteractions(qrCredentialService);
        }

        @Test
        @DisplayName("아동 번호를 경로에 넣던 옛 발급 경로는 없다 — 점주 403, 관리자는 처리할 곳이 없어 404")
        void oldQrPathIsGone() throws Exception {
            expectProblem(mockMvc.perform(post("/api/children/7/qr-token")
                    .header(HttpHeaders.AUTHORIZATION, bearer(MemberRole.OWNER))), ErrorCode.FORBIDDEN);
            mockMvc.perform(post("/api/children/7/qr-token").header(HttpHeaders.AUTHORIZATION, bearer(MemberRole.ADMIN)))
                    .andExpect(status().isNotFound());

            verifyNoInteractions(qrCredentialService);
        }
    }

    @Nested
    @DisplayName("가게 상세 조회·운영 정보 저장 — 점주 앱의 홈·가게 관리")
    class StoreDetailAndOperatingInfo {

        private static final String DETAIL = "/api/stores/1";
        private static final String OPERATING_INFO = "/api/stores/1/operating-info";
        private static final String SAVE_BODY = "{\"phone\":\"053-123-4567\","
                + "\"businessHours\":[{\"day\":\"MON\",\"open\":\"11:00\",\"close\":\"21:00\"}],"
                + "\"menus\":[{\"name\":\"김치찌개\",\"onSale\":true}]}";

        private ResultActions save(String authorization) throws Exception {
            var request = put(OPERATING_INFO).contentType(MediaType.APPLICATION_JSON).content(SAVE_BODY);
            return mockMvc.perform(authorization == null ? request : request.header(HttpHeaders.AUTHORIZATION, authorization));
        }

        @Test
        @DisplayName("점주·관리자는 컨트롤러까지 닿는다 — 연결된 가게인지는 서비스가 본다")
        void ownerAndAdminReachController() throws Exception {
            for (MemberRole role : List.of(MemberRole.OWNER, MemberRole.ADMIN)) {
                mockMvc.perform(get(DETAIL).header(HttpHeaders.AUTHORIZATION, bearer(role)))
                        .andExpect(status().isOk());
                save(bearer(role)).andExpect(status().isOk());
            }
        }

        @Test
        @DisplayName("토큰 없이는 401")
        void anonymousIsUnauthorized() throws Exception {
            expectProblem(mockMvc.perform(get(DETAIL)), ErrorCode.UNAUTHORIZED);
            expectProblem(save(null), ErrorCode.UNAUTHORIZED);
        }

        @Test
        @DisplayName("가입 상태 확인용 토큰과 서버 간 API 키는 403 — 승인 전 신청자·AI 서버는 가게를 보거나 고칠 수 없다")
        void signupStatusAndApiKeyAreForbidden() throws Exception {
            String status = "Bearer " + jwtProvider.issueSignupStatus(1L).value();
            expectProblem(mockMvc.perform(get(DETAIL).header(HttpHeaders.AUTHORIZATION, status)), ErrorCode.FORBIDDEN);
            expectProblem(save(status), ErrorCode.FORBIDDEN);
            expectProblem(mockMvc.perform(get(DETAIL).header(ApiKeyAuthenticationFilter.HEADER, API_KEY)),
                    ErrorCode.FORBIDDEN);
            expectProblem(mockMvc.perform(put(OPERATING_INFO).header(ApiKeyAuthenticationFilter.HEADER, API_KEY)
                    .contentType(MediaType.APPLICATION_JSON).content(SAVE_BODY)), ErrorCode.FORBIDDEN);
        }

        @Test
        @DisplayName("숫자 가게 ID 만 열린다 — 국세청 대조 자료는 여전히 관리자·AI 서버만, 가게 목록·수정은 관리자만")
        void ruleDoesNotOpenOtherStorePaths() throws Exception {
            String owner = bearer(MemberRole.OWNER);
            expectProblem(mockMvc.perform(get("/api/stores/nts-checks").header(HttpHeaders.AUTHORIZATION, owner)),
                    ErrorCode.FORBIDDEN);
            mockMvc.perform(get("/api/stores/nts-checks").header(ApiKeyAuthenticationFilter.HEADER, API_KEY))
                    .andExpect(status().isOk());
            expectProblem(mockMvc.perform(get("/api/stores").header(HttpHeaders.AUTHORIZATION, owner)),
                    ErrorCode.FORBIDDEN);
            expectProblem(mockMvc.perform(get("/api/stores/abc").header(HttpHeaders.AUTHORIZATION, owner)),
                    ErrorCode.FORBIDDEN);
            expectProblem(mockMvc.perform(put("/api/stores/1").header(HttpHeaders.AUTHORIZATION, owner)
                    .contentType(MediaType.APPLICATION_JSON).content("{}")), ErrorCode.FORBIDDEN);
            expectProblem(mockMvc.perform(get(OPERATING_INFO).header(HttpHeaders.AUTHORIZATION, owner)),
                    ErrorCode.FORBIDDEN);
            expectProblem(mockMvc.perform(delete(OPERATING_INFO).header(HttpHeaders.AUTHORIZATION, owner)),
                    ErrorCode.FORBIDDEN);
            expectProblem(mockMvc.perform(patch(DETAIL).header(HttpHeaders.AUTHORIZATION, owner)
                    .contentType(MediaType.APPLICATION_JSON).content("{}")), ErrorCode.FORBIDDEN);
            expectProblem(mockMvc.perform(get(DETAIL + "/").header(HttpHeaders.AUTHORIZATION, owner)),
                    ErrorCode.FORBIDDEN);
        }
    }
}
