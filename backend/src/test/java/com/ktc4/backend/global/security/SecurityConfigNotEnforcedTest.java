package com.ktc4.backend.global.security;

import com.ktc4.backend.domain.auth.controller.AuthController;
import com.ktc4.backend.domain.auth.service.AuthService;
import com.ktc4.backend.domain.member.controller.OwnerAdminController;
import com.ktc4.backend.domain.member.service.OwnerApprovalService;
import com.ktc4.backend.domain.store.controller.StoreController;
import com.ktc4.backend.domain.store.service.StoreService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 권한 검사 스위치를 끈 상태(auth.enforce=false) 확인 — 프론트·AI 가 준비되기 전 개발 서버의 동작이다.
 * 켠 상태의 규칙은 {@link SecurityConfigTest} 가 맡는다.
 */
@WebMvcTest({StoreController.class, AuthController.class, OwnerAdminController.class})
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

    @Test
    @DisplayName("내 정보 조회는 꺼져 있어도 토큰이 있어야 한다")
    void meStillRequiresToken() throws Exception {
        mockMvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
    }
}
