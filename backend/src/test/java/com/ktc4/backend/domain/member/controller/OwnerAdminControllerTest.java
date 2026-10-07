package com.ktc4.backend.domain.member.controller;

import com.ktc4.backend.domain.member.dto.OwnerApplicationResponse;
import com.ktc4.backend.domain.member.dto.StoreCandidateResponse;
import com.ktc4.backend.domain.member.enums.MemberRole;
import com.ktc4.backend.domain.member.enums.MemberStatus;
import com.ktc4.backend.domain.member.service.OwnerApprovalService;
import com.ktc4.backend.domain.store.enums.StoreStatus;
import com.ktc4.backend.global.dto.PageResponse;
import com.ktc4.backend.global.error.CustomException;
import com.ktc4.backend.global.error.ErrorCode;
import com.ktc4.backend.global.security.AuthMember;
import com.ktc4.backend.global.security.SecurityConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 점주 가입 관리 API 의 HTTP 계약 확인. 권한 규칙은 {@code SecurityConfigTest} 가 맡는다.
 */
@WebMvcTest(OwnerAdminController.class)
@Import(SecurityConfig.class)
@WithMockUser(roles = "ADMIN")
class OwnerAdminControllerTest {

    private static final long ADMIN_ID = 1L;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private OwnerApprovalService ownerApprovalService;

    private static OwnerApplicationResponse sample(MemberStatus status) {
        return new OwnerApplicationResponse(3L, "owner@example.com", "1234567890", "예시분식", "홍길동",
                "01000000000", status, LocalDateTime.of(2026, 9, 29, 9, 0), null);
    }

    // 승인은 "누가 인정했는지"를 남기므로 실제 토큰 필터가 넣는 것과 같은 모양(AuthMember)으로 로그인시킨다.
    private static RequestPostProcessor adminLogin() {
        AuthMember admin = new AuthMember(ADMIN_ID, MemberRole.ADMIN);
        return authentication(UsernamePasswordAuthenticationToken.authenticated(admin, null, admin.authorities()));
    }

    private static String approveBody(long storeId) {
        return "{\"storeId\":" + storeId + "}";
    }

    @Test
    @DisplayName("상태를 주지 않으면 승인 대기 목록을 조회한다")
    void defaultsToPending() throws Exception {
        when(ownerApprovalService.getApplications(eq(MemberStatus.PENDING), anyInt(), anyInt()))
                .thenReturn(new PageResponse<>(List.of(sample(MemberStatus.PENDING)), 0, 20, 1, 1, false));

        mockMvc.perform(get("/api/admin/owners"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].memberId").value(3))
                .andExpect(jsonPath("$.content[0].bizNo").value("1234567890"))
                .andExpect(jsonPath("$.content[0].phone").value("01000000000"))
                .andExpect(jsonPath("$.content[0].status").value("PENDING"));

        verify(ownerApprovalService).getApplications(MemberStatus.PENDING, 0, 20);
    }

    @Test
    @DisplayName("잘못된 상태 값이나 범위를 벗어난 limit 은 400")
    void rejectsInvalidParams() throws Exception {
        mockMvc.perform(get("/api/admin/owners").param("status", "UNKNOWN")).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/admin/owners").param("limit", "101")).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/admin/owners").param("page", "-1")).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("후보 가게는 어느 단서가 맞았는지와 이미 연결된 점주 수를 함께 내려준다")
    void listsStoreCandidates() throws Exception {
        when(ownerApprovalService.getStoreCandidates(3L)).thenReturn(List.of(
                new StoreCandidateResponse(10L, "예시분식", "가상특별시 예시구 샘플로 123", "1234567890",
                        "010-****-0000", StoreStatus.OPEN, true, false, 1)));

        mockMvc.perform(get("/api/admin/owners/3/store-candidates"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].storeId").value(10))
                .andExpect(jsonPath("$[0].name").value("예시분식"))
                .andExpect(jsonPath("$[0].phone").value("010-****-0000"))
                .andExpect(jsonPath("$[0].bizNoMatched").value(true))
                .andExpect(jsonPath("$[0].phoneMatched").value(false))
                .andExpect(jsonPath("$[0].linkedOwnerCount").value(1));
    }

    @Test
    @DisplayName("후보가 없으면 빈 배열이고, 없는 점주는 404")
    void candidatesEmptyOrNotFound() throws Exception {
        when(ownerApprovalService.getStoreCandidates(3L)).thenReturn(List.of());
        when(ownerApprovalService.getStoreCandidates(99L)).thenThrow(new CustomException(ErrorCode.MEMBER_NOT_FOUND));

        mockMvc.perform(get("/api/admin/owners/3/store-candidates"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
        mockMvc.perform(get("/api/admin/owners/99/store-candidates"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("승인하면 고른 가게와 로그인한 관리자를 서비스에 넘기고, 승인된 신청을 돌려준다")
    void approves() throws Exception {
        when(ownerApprovalService.approve(3L, 10L, ADMIN_ID)).thenReturn(sample(MemberStatus.APPROVED));

        mockMvc.perform(post("/api/admin/owners/3/approve").with(adminLogin())
                        .contentType(MediaType.APPLICATION_JSON).content(approveBody(10L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));

        verify(ownerApprovalService).approve(3L, 10L, ADMIN_ID);
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {"{}", "{\"storeId\":null}"})
    @DisplayName("가게를 고르지 않으면 400 이고 승인하지 않는다 — 가게 없는 점주를 만들지 않는다")
    void rejectsApproveWithoutStore(String body) throws Exception {
        mockMvc.perform(post("/api/admin/owners/3/approve").with(adminLogin())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value(ErrorCode.INVALID_REQUEST.getType().toString()))
                .andExpect(jsonPath("$.errors[0].field").value("storeId"));

        verify(ownerApprovalService, never()).approve(anyLong(), any(), any());
    }

    @Test
    @DisplayName("본문 없이 부르면 400 — 예전 방식(본문 없는 승인)은 더 이상 통하지 않는다")
    void rejectsApproveWithoutBody() throws Exception {
        mockMvc.perform(post("/api/admin/owners/3/approve").with(adminLogin()))
                .andExpect(status().isBadRequest());

        verify(ownerApprovalService, never()).approve(anyLong(), any(), any());
    }

    @Test
    @DisplayName("이미 처리된 신청은 409, 없는 점주·없는 가게는 404")
    void mapsErrors() throws Exception {
        when(ownerApprovalService.approve(3L, 10L, ADMIN_ID))
                .thenThrow(new CustomException(ErrorCode.OWNER_ALREADY_REVIEWED));
        when(ownerApprovalService.approve(99L, 10L, ADMIN_ID))
                .thenThrow(new CustomException(ErrorCode.MEMBER_NOT_FOUND));
        when(ownerApprovalService.approve(3L, 999L, ADMIN_ID))
                .thenThrow(new CustomException(ErrorCode.STORE_NOT_FOUND));

        mockMvc.perform(post("/api/admin/owners/3/approve").with(adminLogin())
                        .contentType(MediaType.APPLICATION_JSON).content(approveBody(10L)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value(ErrorCode.OWNER_ALREADY_REVIEWED.getType().toString()));
        mockMvc.perform(post("/api/admin/owners/99/approve").with(adminLogin())
                        .contentType(MediaType.APPLICATION_JSON).content(approveBody(10L)))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/admin/owners/3/approve").with(adminLogin())
                        .contentType(MediaType.APPLICATION_JSON).content(approveBody(999L)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value(ErrorCode.STORE_NOT_FOUND.getType().toString()));
    }
}
