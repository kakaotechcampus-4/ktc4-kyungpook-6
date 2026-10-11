package com.ktc4.backend.domain.job.controller;

import com.ktc4.backend.domain.investigation.dto.InvestigationPlan;
import com.ktc4.backend.domain.investigation.service.InvestigationRunner;
import com.ktc4.backend.domain.job.dto.CreatedJob;
import com.ktc4.backend.domain.job.dto.JobCreateRequest;
import com.ktc4.backend.domain.job.service.JobQueryService;
import com.ktc4.backend.domain.job.service.JobService;
import com.ktc4.backend.domain.member.enums.MemberRole;
import com.ktc4.backend.domain.store.dto.ExcludedStore;
import com.ktc4.backend.domain.store.enums.InvestigationExclusionReason;
import com.ktc4.backend.global.error.CustomException;
import com.ktc4.backend.global.error.ErrorCode;
import com.ktc4.backend.global.security.JwtProvider;
import com.ktc4.backend.global.security.SecurityConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.test.web.servlet.ResultActions;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 조사 시작 API({@code POST /api/jobs}) 의 HTTP 계약.
 *
 * <p>로그인 관리자의 회원 ID 를 요청 스레드에서 읽어야 해서({@code SecurityContext} 는 {@code @Async} 스레드로
 * 넘어가지 않는다) {@code @WithMockUser} 대신 실제 토큰을 붙인다.
 */
@WebMvcTest(JobController.class)
@Import(SecurityConfig.class)
@TestPropertySource(properties = "auth.enforce=true")
@DisplayName("조사 시작 API")
class JobControllerCreateTest {

    private static final String JOBS_PATH = "/api/jobs";
    private static final long ADMIN_ID = 7L;

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

    private ResultActions postJobs(String body, MemberRole role) throws Exception {
        return mockMvc.perform(post(JOBS_PATH)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwtProvider.issue(ADMIN_ID, role).value())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    @Test
    @DisplayName("202 로 jobId·대상 수·제외 목록을 바로 돌려주고, 저장이 끝난 뒤 실행기를 부른다")
    void acceptsAndStartsRunnerAfterCreate() throws Exception {
        InvestigationPlan plan = new InvestigationPlan(42L, List.of(), List.of());
        given(jobService.create(List.of(1L, 5L, 7L), ADMIN_ID)).willReturn(new CreatedJob(
                42L, 2, List.of(new ExcludedStore(7L, InvestigationExclusionReason.DATA_PROBLEM)), plan));

        postJobs("{\"storeIds\": [1, 5, 7]}", MemberRole.ADMIN)
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.jobId").value(42))
                .andExpect(jsonPath("$.targetCount").value(2))
                .andExpect(jsonPath("$.excluded.length()").value(1))
                .andExpect(jsonPath("$.excluded[0].storeId").value(7))
                .andExpect(jsonPath("$.excluded[0].reason").value("DATA_PROBLEM"));

        // create() 의 트랜잭션이 끝난(커밋된) 뒤에 실행기를 불러야 실행기 스레드가 Job 을 읽을 수 있다
        InOrder order = inOrder(jobService, investigationRunner);
        order.verify(jobService).create(List.of(1L, 5L, 7L), ADMIN_ID);
        order.verify(investigationRunner).run(plan);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}",
            "{\"storeIds\": null}",
            "{\"storeIds\": []}",
            "{\"storeIds\": [1, null]}",
            "{\"storeIds\": [0]}",
            "{\"storeIds\": [-1]}"})
    @DisplayName("가게 목록이 없거나 비었거나 잘못된 번호가 있으면 400 이고 Job 을 만들지 않는다")
    void rejectsInvalidStoreIds(String body) throws Exception {
        postJobs(body, MemberRole.ADMIN)
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value(ErrorCode.INVALID_REQUEST.getType().toString()));

        verify(jobService, never()).create(anyList(), anyLong());
        verify(investigationRunner, never()).run(any());
    }

    @Test
    @DisplayName("요청 본문에 가게 ID 가 1000개를 넘으면 서비스까지 가지 않고 400")
    void rejectsOversizedRequest() throws Exception {
        String ids = LongStream.rangeClosed(1, JobCreateRequest.MAX_REQUEST_SIZE + 1)
                .mapToObj(String::valueOf).collect(Collectors.joining(","));

        postJobs("{\"storeIds\": [" + ids + "]}", MemberRole.ADMIN)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value(ErrorCode.INVALID_REQUEST.getType().toString()));

        verify(jobService, never()).create(anyList(), anyLong());
    }

    @Test
    @DisplayName("조사할 가게가 없으면 400 no-investigation-target 이고 실행기를 부르지 않는다")
    void rejectsWhenNothingToInvestigate() throws Exception {
        given(jobService.create(List.of(7L), ADMIN_ID))
                .willThrow(new CustomException(ErrorCode.NO_INVESTIGATION_TARGET));

        postJobs("{\"storeIds\": [7]}", MemberRole.ADMIN)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value(ErrorCode.NO_INVESTIGATION_TARGET.getType().toString()))
                .andExpect(jsonPath("$.title").value(ErrorCode.NO_INVESTIGATION_TARGET.getTitle()));

        verify(investigationRunner, never()).run(any());
    }

    @Test
    @DisplayName("진행 중인 조사가 있으면 409 job-already-running 이고 실행기를 부르지 않는다")
    void rejectsWhileAnotherJobIsRunning() throws Exception {
        given(jobService.create(List.of(1L), ADMIN_ID))
                .willThrow(new CustomException(ErrorCode.JOB_ALREADY_RUNNING));

        postJobs("{\"storeIds\": [1]}", MemberRole.ADMIN)
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value(ErrorCode.JOB_ALREADY_RUNNING.getType().toString()))
                .andExpect(jsonPath("$.title").value(ErrorCode.JOB_ALREADY_RUNNING.getTitle()));

        verify(investigationRunner, never()).run(any());
    }

    @Test
    @DisplayName("실행기에 넘기지 못하면 만든 Job 을 실패로 끝내 둔다 — 대기로 남아 새 조사를 모두 막지 않게")
    void failsJobWhenRunnerCannotStart() throws Exception {
        InvestigationPlan plan = new InvestigationPlan(42L, List.of(), List.of());
        given(jobService.create(List.of(1L), ADMIN_ID)).willReturn(new CreatedJob(42L, 1, List.of(), plan));
        willThrow(new TaskRejectedException("종료 중")).given(investigationRunner).run(plan);

        postJobs("{\"storeIds\": [1]}", MemberRole.ADMIN)
                .andExpect(status().isInternalServerError());

        verify(jobService).fail(eq(42L), eq(JobController.ERROR_NOT_STARTED), any());
    }

    @Test
    @DisplayName("토큰이 없으면 401")
    void rejectsWithoutToken() throws Exception {
        mockMvc.perform(post(JOBS_PATH).contentType(MediaType.APPLICATION_JSON).content("{\"storeIds\": [1]}"))
                .andExpect(status().isUnauthorized());

        verify(jobService, never()).create(anyList(), anyLong());
    }

    @Test
    @DisplayName("점주는 403 — 조사는 관리자만 시작한다")
    void rejectsOwner() throws Exception {
        postJobs("{\"storeIds\": [1]}", MemberRole.OWNER)
                .andExpect(status().isForbidden());

        verify(jobService, never()).create(anyList(), anyLong());
    }
}
