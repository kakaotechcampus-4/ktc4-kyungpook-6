package com.ktc4.backend.domain.task.controller;

import com.ktc4.backend.domain.member.enums.MemberRole;
import com.ktc4.backend.domain.store.enums.StoreStatus;
import com.ktc4.backend.domain.task.dto.ProposedChangeResponse;
import com.ktc4.backend.domain.task.dto.TaskResultResponse;
import com.ktc4.backend.domain.task.enums.TaskClassification;
import com.ktc4.backend.domain.verification.dto.VerificationResponse;
import com.ktc4.backend.domain.verification.enums.VerificationAction;
import com.ktc4.backend.domain.verification.service.VerificationService;
import com.ktc4.backend.global.error.CustomException;
import com.ktc4.backend.global.error.ErrorCode;
import com.ktc4.backend.global.security.JwtProvider;
import com.ktc4.backend.global.security.SecurityConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 결과 카드의 "즉시 수정 반영하기"·"자체 확인 완료" API 의 HTTP 계약.
 *
 * <p>확인한 관리자를 기록해야 해서 {@code @WithMockUser} 대신 실제 토큰을 붙인다.
 */
@WebMvcTest(TaskController.class)
@Import(SecurityConfig.class)
@TestPropertySource(properties = "auth.enforce=true")
@DisplayName("수정안 반영·확인 API")
class TaskControllerTest {

    private static final long ADMIN_ID = 7L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtProvider jwtProvider;

    @MockitoBean
    private VerificationService verificationService;

    private ResultActions postAs(String path, MemberRole role) throws Exception {
        return mockMvc.perform(post(path)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwtProvider.issue(ADMIN_ID, role).value()));
    }

    private static TaskResultResponse confirmedCard(VerificationAction action) {
        return new TaskResultResponse(1L, 5L, "맛나 치킨", "대구광역시 북구 대학로 80", StoreStatus.CLOSED, "053-111-1111",
                LocalDateTime.of(2026, 10, 7, 12, 0), TaskClassification.PRIORITY_CHECK, null,
                List.of(new ProposedChangeResponse("status", "CLOSED")), List.of(),
                new VerificationResponse(action, LocalDateTime.of(2026, 10, 7, 12, 0)));
    }

    @Test
    @DisplayName("반영하면 확인 기록이 담긴 카드 한 장을 돌려준다 — 화면이 그 카드만 '확인 완료됨'으로 바꾼다")
    void appliesAndReturnsCard() throws Exception {
        given(verificationService.apply(1L, ADMIN_ID)).willReturn(confirmedCard(VerificationAction.CHANGE_STATUS));

        postAs("/api/tasks/1/apply", MemberRole.ADMIN)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskId").value(1))
                .andExpect(jsonPath("$.storeStatus").value("CLOSED"))
                .andExpect(jsonPath("$.lastCheckedAt").value("2026-10-07T12:00:00"))
                .andExpect(jsonPath("$.verification.action").value("CHANGE_STATUS"))
                .andExpect(jsonPath("$.verification.verifiedAt").value("2026-10-07T12:00:00"));
    }

    @Test
    @DisplayName("확인 완료하면 확인 기록이 담긴 카드 한 장을 돌려준다")
    void confirmsAndReturnsCard() throws Exception {
        given(verificationService.confirm(1L, ADMIN_ID)).willReturn(confirmedCard(VerificationAction.NO_ACTION));

        postAs("/api/tasks/1/confirm", MemberRole.ADMIN)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verification.action").value("NO_ACTION"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"apply", "confirm"})
    @DisplayName("없는 조사 결과는 404 task-not-found")
    void notFound(String action) throws Exception {
        given(verificationService.apply(99L, ADMIN_ID)).willThrow(new CustomException(ErrorCode.TASK_NOT_FOUND));
        given(verificationService.confirm(99L, ADMIN_ID)).willThrow(new CustomException(ErrorCode.TASK_NOT_FOUND));

        postAs("/api/tasks/99/" + action, MemberRole.ADMIN)
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value(ErrorCode.TASK_NOT_FOUND.getType().toString()));
    }

    @Test
    @DisplayName("이미 확인했으면 409 task-already-confirmed, 반영할 수정안이 없으면 409 nothing-to-apply — type 으로 나뉜다")
    void conflictsAreDistinguishedByType() throws Exception {
        given(verificationService.apply(1L, ADMIN_ID)).willThrow(new CustomException(ErrorCode.TASK_ALREADY_CONFIRMED));
        given(verificationService.apply(2L, ADMIN_ID)).willThrow(new CustomException(ErrorCode.NOTHING_TO_APPLY));

        postAs("/api/tasks/1/apply", MemberRole.ADMIN)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value(ErrorCode.TASK_ALREADY_CONFIRMED.getType().toString()))
                .andExpect(jsonPath("$.title").value(ErrorCode.TASK_ALREADY_CONFIRMED.getTitle()));
        postAs("/api/tasks/2/apply", MemberRole.ADMIN)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value(ErrorCode.NOTHING_TO_APPLY.getType().toString()))
                .andExpect(jsonPath("$.title").value(ErrorCode.NOTHING_TO_APPLY.getTitle()));
    }

    @Test
    @DisplayName("이미 확인한 카드를 다시 확인 완료하면 409 task-already-confirmed")
    void confirmConflict() throws Exception {
        given(verificationService.confirm(1L, ADMIN_ID)).willThrow(new CustomException(ErrorCode.TASK_ALREADY_CONFIRMED));

        postAs("/api/tasks/1/confirm", MemberRole.ADMIN)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value(ErrorCode.TASK_ALREADY_CONFIRMED.getType().toString()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"apply", "confirm"})
    @DisplayName("토큰이 없으면 401, 점주는 403 — 가게 정보를 바꾸는 API 라 관리자만")
    void requiresAdmin(String action) throws Exception {
        mockMvc.perform(post("/api/tasks/1/" + action)).andExpect(status().isUnauthorized());
        postAs("/api/tasks/1/" + action, MemberRole.OWNER).andExpect(status().isForbidden());

        verify(verificationService, never()).apply(anyLong(), anyLong());
        verify(verificationService, never()).confirm(anyLong(), anyLong());
    }
}
