package com.ktc4.backend.global.security;

import com.ktc4.backend.domain.auth.controller.AuthController;
import com.ktc4.backend.domain.auth.service.AuthService;
import com.ktc4.backend.domain.investigation.service.InvestigationRunner;
import com.ktc4.backend.domain.job.controller.JobController;
import com.ktc4.backend.domain.job.service.JobQueryService;
import com.ktc4.backend.domain.job.service.JobService;
import com.ktc4.backend.domain.member.controller.OwnerAdminController;
import com.ktc4.backend.domain.member.enums.MemberRole;
import com.ktc4.backend.domain.member.service.OwnerApprovalService;
import com.ktc4.backend.domain.qr.controller.QrCredentialController;
import com.ktc4.backend.domain.qr.service.QrCredentialService;
import com.ktc4.backend.domain.store.controller.StoreController;
import com.ktc4.backend.domain.store.service.StoreService;
import com.ktc4.backend.global.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
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

import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 권한 검사 스위치를 끈 상태(auth.enforce=false) 확인 — 프론트·AI 가 준비되기 전 개발 서버의 동작이다.
 * 켠 상태의 규칙은 {@link SecurityConfigTest} 가 맡는다.
 */
@WebMvcTest({StoreController.class, AuthController.class, OwnerAdminController.class, QrCredentialController.class,
        JobController.class})
@Import(SecurityConfig.class)
@TestPropertySource(properties = "auth.enforce=false")
@DisplayName("API 접근 권한 — 검사 꺼짐")
class SecurityConfigNotEnforcedTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private StoreService storeService;

    @MockitoBean
    private AuthService authService;

    @MockitoBean
    private OwnerApprovalService ownerApprovalService;

    @MockitoBean
    private QrCredentialService qrCredentialService;

    @MockitoBean
    private JobService jobService;

    @MockitoBean
    private JobQueryService jobQueryService;

    @MockitoBean
    private InvestigationRunner investigationRunner;

    @Autowired
    private JwtProvider jwtProvider;

    @Test
    @DisplayName("토큰 없이도 기존 API 를 지금처럼 부를 수 있다")
    void allowsExistingApisWithoutToken() throws Exception {
        mockMvc.perform(get("/api/stores")).andExpect(status().isOk());
        mockMvc.perform(get("/api/stores/nts-checks")).andExpect(status().isOk());
        mockMvc.perform(patch("/api/stores/1").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("로그인 API 는 동작한다")
    void allowsLogin() throws Exception {
        mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"owner@example.com\",\"password\":\"password1234\"}"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("점주 승인 API 는 꺼져 있어도 관리자만 — 아무나 점주를 승인할 수 없게")
    void ownerAdminApiAlwaysRequiresAdmin() throws Exception {
        mockMvc.perform(get("/api/admin/owners")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/admin/owners/3/approve")).andExpect(status().isUnauthorized());
    }

    // 승인은 "누가 인정했는지"를 남기므로 로그인한 관리자가 반드시 있어야 한다. 스위치가 꺼져 있어도 토큰 없는 요청이
    // 서비스까지 닿지 않는다는 것과, 그때의 응답이 에러 처리 가이드의 규격(unauthorized / forbidden)이라는 것을 고정한다.
    @Test
    @DisplayName("꺼져 있어도 토큰 없는 승인·후보 조회는 가이드의 unauthorized 로 거절되고 서비스에 닿지 않는다")
    void approveNeverReachesServiceWithoutAdmin() throws Exception {
        expectProblem(mockMvc.perform(post("/api/admin/owners/3/approve")
                .contentType(MediaType.APPLICATION_JSON).content("{\"storeId\":10}")), ErrorCode.UNAUTHORIZED);
        expectProblem(mockMvc.perform(get("/api/admin/owners/3/store-candidates")), ErrorCode.UNAUTHORIZED);

        verifyNoInteractions(ownerApprovalService);
    }

    @Test
    @DisplayName("꺼져 있어도 점주 토큰의 승인·후보 조회는 가이드의 forbidden 으로 거절된다 — 점주가 스스로를 승인할 수 없다")
    void ownerCannotApprove() throws Exception {
        String owner = "Bearer " + jwtProvider.issue(3L, MemberRole.OWNER).value();

        expectProblem(mockMvc.perform(post("/api/admin/owners/3/approve").header(HttpHeaders.AUTHORIZATION, owner)
                .contentType(MediaType.APPLICATION_JSON).content("{\"storeId\":10}")), ErrorCode.FORBIDDEN);
        expectProblem(mockMvc.perform(get("/api/admin/owners/3/store-candidates")
                .header(HttpHeaders.AUTHORIZATION, owner)), ErrorCode.FORBIDDEN);

        verifyNoInteractions(ownerApprovalService);
    }

    // 보안 필터의 거절 응답이 docs/에러_처리_가이드.md 의 모양(RFC 9457)인지 본다 — SecurityConfigTest 와 같은 확인이다.
    private static void expectProblem(ResultActions result, ErrorCode errorCode) throws Exception {
        result.andExpect(status().is(errorCode.getHttpStatus().value()))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value(errorCode.getType().toString()))
                .andExpect(jsonPath("$.title").value(errorCode.getTitle()))
                .andExpect(jsonPath("$.status").value(errorCode.getHttpStatus().value()))
                .andExpect(jsonPath("$.detail").doesNotExist());
    }

    @Test
    @DisplayName("아동 QR 발급은 꺼져 있어도 관리자만 — 아무나 재발급해 남의 QR 을 무효로 만들 수 없게")
    void qrIssueAlwaysRequiresAdmin() throws Exception {
        mockMvc.perform(post("/api/children/7/qr-token")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/children/7/qr-token")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwtProvider.issue(1L, MemberRole.OWNER).value()))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/children/7/qr-token")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwtProvider.issue(1L, MemberRole.ADMIN).value()))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("조사 API 는 꺼져 있어도 관리자만 — AI 호출 비용이 드는 API 라 아무나 돌릴 수 없게")
    void jobApiAlwaysRequiresAdmin() throws Exception {
        mockMvc.perform(post("/api/jobs").contentType(MediaType.APPLICATION_JSON).content("{\"storeIds\":[1]}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/jobs/1")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/jobs/latest")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/jobs/1")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwtProvider.issue(1L, MemberRole.OWNER).value()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("내 정보 조회는 꺼져 있어도 토큰이 있어야 한다")
    void meStillRequiresToken() throws Exception {
        mockMvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
    }
}
