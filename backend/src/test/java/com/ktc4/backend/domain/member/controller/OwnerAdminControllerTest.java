package com.ktc4.backend.domain.member.controller;

import com.ktc4.backend.domain.member.dto.OwnerApplicationResponse;
import com.ktc4.backend.domain.member.enums.MemberStatus;
import com.ktc4.backend.domain.member.service.OwnerApprovalService;
import com.ktc4.backend.global.dto.PageResponse;
import com.ktc4.backend.global.error.CustomException;
import com.ktc4.backend.global.error.ErrorCode;
import com.ktc4.backend.global.security.SecurityConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
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

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private OwnerApprovalService ownerApprovalService;

    private static OwnerApplicationResponse sample(MemberStatus status) {
        return new OwnerApplicationResponse(3L, "owner@example.com", "1234567890", "예시분식", "홍길동",
                status, LocalDateTime.of(2026, 9, 29, 9, 0), null);
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
    @DisplayName("승인하면 승인된 신청을 돌려준다")
    void approves() throws Exception {
        when(ownerApprovalService.approve(3L)).thenReturn(sample(MemberStatus.APPROVED));

        mockMvc.perform(post("/api/admin/owners/3/approve"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));
    }

    @Test
    @DisplayName("이미 처리된 신청은 409, 없는 점주는 404")
    void mapsErrors() throws Exception {
        when(ownerApprovalService.approve(3L)).thenThrow(new CustomException(ErrorCode.OWNER_ALREADY_REVIEWED));
        when(ownerApprovalService.approve(99L)).thenThrow(new CustomException(ErrorCode.MEMBER_NOT_FOUND));

        mockMvc.perform(post("/api/admin/owners/3/approve"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value(ErrorCode.OWNER_ALREADY_REVIEWED.getType().toString()));
        mockMvc.perform(post("/api/admin/owners/99/approve"))
                .andExpect(status().isNotFound());
    }
}
