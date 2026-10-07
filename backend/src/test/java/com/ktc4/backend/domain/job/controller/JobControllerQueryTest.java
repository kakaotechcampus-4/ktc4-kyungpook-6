package com.ktc4.backend.domain.job.controller;

import com.ktc4.backend.domain.investigation.service.InvestigationRunner;
import com.ktc4.backend.domain.job.dto.JobResultResponse;
import com.ktc4.backend.domain.job.enums.JobStatus;
import com.ktc4.backend.domain.job.service.JobQueryService;
import com.ktc4.backend.domain.job.service.JobService;
import com.ktc4.backend.domain.member.enums.MemberRole;
import com.ktc4.backend.domain.store.enums.StoreStatus;
import com.ktc4.backend.domain.task.dto.EvidenceResponse;
import com.ktc4.backend.domain.task.dto.ProposedChangeResponse;
import com.ktc4.backend.domain.task.dto.TaskResultResponse;
import com.ktc4.backend.domain.task.enums.TaskClassification;
import com.ktc4.backend.global.error.CustomException;
import com.ktc4.backend.global.error.ErrorCode;
import com.ktc4.backend.global.security.JwtProvider;
import com.ktc4.backend.global.security.SecurityConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.LocalDateTime;
import java.util.List;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 조사 진행도·결과 조회 API 의 HTTP 계약. 화면이 이 필드 이름으로 그리므로 백엔드 사정으로 조용히 바뀌지 않게 고정한다.
 */
@WebMvcTest(JobController.class)
@Import(SecurityConfig.class)
@TestPropertySource(properties = "auth.enforce=true")
@DisplayName("조사 조회 API")
class JobControllerQueryTest {

    private static final LocalDateTime FINISHED_AT = LocalDateTime.of(2026, 10, 6, 12, 30);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtProvider jwtProvider;

    @MockitoBean
    private JobService jobService;

    @MockitoBean
    private JobQueryService jobQueryService;

    @MockitoBean
    private InvestigationRunner investigationRunner;

    private ResultActions getAsAdmin(String path) throws Exception {
        return mockMvc.perform(get(path)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwtProvider.issue(1L, MemberRole.ADMIN).value()));
    }

    private static TaskResultResponse priorityTask() {
        return new TaskResultResponse(1L, 5L, "폐업가게", "대구광역시 북구 대학로 80", StoreStatus.OPEN, "053-111-1111",
                LocalDateTime.of(2026, 7, 1, 9, 0), TaskClassification.PRIORITY_CHECK, null,
                List.of(new ProposedChangeResponse("status", "CLOSED")),
                List.of(new EvidenceResponse("status", "NTS", "국세청 사업자 상태: 폐업자", null, null)));
    }

    private static TaskResultResponse failedTask() {
        return new TaskResultResponse(2L, 6L, "실패가게", "대구광역시 북구 대학로 81", StoreStatus.OPEN, null,
                null, null, "AI 응답 시간이 초과됐습니다", List.of(), List.of());
    }

    @ParameterizedTest
    @EnumSource(JobStatus.class)
    @DisplayName("상태마다 진행도·결과 필드 이름이 같다")
    void sameShapeForEveryStatus(JobStatus jobStatus) throws Exception {
        boolean finished = jobStatus == JobStatus.DONE || jobStatus == JobStatus.FAILED;
        given(jobQueryService.getJob(42L)).willReturn(new JobResultResponse(42L, jobStatus, 2, finished ? 2 : 1,
                finished ? FINISHED_AT : null, jobStatus == JobStatus.FAILED ? "AI 조사를 쓸 수 없어 조사를 멈췄습니다" : null,
                List.of(priorityTask(), failedTask())));

        getAsAdmin("/api/jobs/42")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobId").value(42))
                .andExpect(jsonPath("$.status").value(jobStatus.name()))
                .andExpect(jsonPath("$.targetCount").value(2))
                .andExpect(jsonPath("$.completedCount").value(finished ? 2 : 1))
                .andExpect(finished ? jsonPath("$.finishedAt").value("2026-10-06T12:30:00")
                        : jsonPath("$.finishedAt").value(nullValue()))
                .andExpect(jsonPath("$.tasks[0].taskId").value(1))
                .andExpect(jsonPath("$.tasks[0].storeId").value(5))
                .andExpect(jsonPath("$.tasks[0].storeName").value("폐업가게"))
                .andExpect(jsonPath("$.tasks[0].storeAddress").value("대구광역시 북구 대학로 80"))
                .andExpect(jsonPath("$.tasks[0].storeStatus").value("OPEN"))
                .andExpect(jsonPath("$.tasks[0].storePhone").value("053-111-1111"))
                .andExpect(jsonPath("$.tasks[0].lastCheckedAt").value("2026-07-01T09:00:00"))
                .andExpect(jsonPath("$.tasks[0].classification").value("PRIORITY_CHECK"))
                .andExpect(jsonPath("$.tasks[0].failureReason").value(nullValue()))
                .andExpect(jsonPath("$.tasks[0].proposedChanges[0].field").value("status"))
                .andExpect(jsonPath("$.tasks[0].proposedChanges[0].value").value("CLOSED"))
                .andExpect(jsonPath("$.tasks[0].evidences[0].field").value("status"))
                .andExpect(jsonPath("$.tasks[0].evidences[0].source").value("NTS"))
                .andExpect(jsonPath("$.tasks[0].evidences[0].description").value("국세청 사업자 상태: 폐업자"))
                .andExpect(jsonPath("$.tasks[0].evidences[0].sourceLabel").value(nullValue()))
                .andExpect(jsonPath("$.tasks[0].evidences[0].sourceUrl").value(nullValue()))
                // 분류가 null 이면 조사 실패 — 프론트가 이 값으로 실패 섹션을 나눈다
                .andExpect(jsonPath("$.tasks[1].classification").value(nullValue()))
                .andExpect(jsonPath("$.tasks[1].failureReason").value("AI 응답 시간이 초과됐습니다"))
                .andExpect(jsonPath("$.tasks[1].proposedChanges").isEmpty())
                .andExpect(jsonPath("$.tasks[1].evidences").isEmpty());
    }

    @Test
    @DisplayName("실패한 조사는 멈춘 이유를 내려준다")
    void failedJobHasErrorMessage() throws Exception {
        given(jobQueryService.getJob(42L)).willReturn(new JobResultResponse(42L, JobStatus.FAILED, 2, 1,
                FINISHED_AT, "AI 조사를 쓸 수 없어 조사를 멈췄습니다", List.of(priorityTask())));

        getAsAdmin("/api/jobs/42")
                .andExpect(jsonPath("$.errorMessage").value("AI 조사를 쓸 수 없어 조사를 멈췄습니다"));
    }

    @Test
    @DisplayName("/latest 는 조사 ID 로 읽히지 않고 가장 최근 조사를 내려준다")
    void latestIsNotParsedAsJobId() throws Exception {
        given(jobQueryService.getLatest()).willReturn(new JobResultResponse(
                43L, JobStatus.DONE, 1, 1, FINISHED_AT, null, List.of(priorityTask())));

        getAsAdmin("/api/jobs/latest")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobId").value(43));

        verify(jobQueryService, never()).getJob(anyLong());
    }

    @Test
    @DisplayName("없는 조사는 404 job-not-found")
    void notFound() throws Exception {
        given(jobQueryService.getJob(99L)).willThrow(new CustomException(ErrorCode.JOB_NOT_FOUND));

        getAsAdmin("/api/jobs/99")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value(ErrorCode.JOB_NOT_FOUND.getType().toString()));
    }

    @Test
    @DisplayName("조사가 하나도 없으면 /latest 도 404 job-not-found")
    void latestNotFound() throws Exception {
        given(jobQueryService.getLatest()).willThrow(new CustomException(ErrorCode.JOB_NOT_FOUND));

        getAsAdmin("/api/jobs/latest")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value(ErrorCode.JOB_NOT_FOUND.getType().toString()));
    }

    @Test
    @DisplayName("점주는 403")
    void rejectsOwner() throws Exception {
        mockMvc.perform(get("/api/jobs/42")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwtProvider.issue(1L, MemberRole.OWNER).value()))
                .andExpect(status().isForbidden());
    }
}
