package com.ktc4.backend.domain.job.service;

import com.ktc4.backend.domain.job.dto.JobResultResponse;
import com.ktc4.backend.domain.job.entity.Job;
import com.ktc4.backend.domain.job.repository.JobRepository;
import com.ktc4.backend.domain.task.dto.TaskResultResponse;
import com.ktc4.backend.domain.task.service.TaskService;
import com.ktc4.backend.domain.verification.dto.VerificationResponse;
import com.ktc4.backend.domain.verification.service.VerificationService;
import com.ktc4.backend.global.error.CustomException;
import com.ktc4.backend.global.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * 조사 진행도와 결과를 읽는다.
 *
 * <p>{@link JobService} 와 나눈 이유는 의존 방향이다. 쓰기는 task → job({@code TaskService} 가 {@code JobService} 로
 * 진행 수를 올림)이고 조회는 job → task(이 서비스가 {@code TaskService} 로 결과를 읽음)라, 한 클래스로 합치면
 * {@code JobService ↔ TaskService} 빈 순환이 생겨 앱이 뜨지 않는다.
 *
 * <p>가게별 확인 기록도 같은 이유로 여기서 붙인다 — {@code VerificationService} 가 반영할 때 {@code TaskService} 로
 * Task 를 잠그므로, {@code TaskService} 가 확인 기록을 읽으면 둘 사이에 순환이 생긴다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class JobQueryService {

    private final JobRepository jobRepository;
    private final TaskService taskService;
    private final VerificationService verificationService;

    /**
     * 조사 한 건의 진행도와 지금까지 끝난 가게의 결과를 읽는다.
     *
     * @param jobId 조사 ID
     * @return 진행도와 결과
     * @throws CustomException 조사가 없으면 {@code JOB_NOT_FOUND}
     */
    public JobResultResponse getJob(Long jobId) {
        Job job = jobRepository.findById(jobId)
                .orElseThrow(() -> new CustomException(ErrorCode.JOB_NOT_FOUND));
        return JobResultResponse.of(job, withVerifications(taskService.findResults(jobId)));
    }

    /**
     * 가장 최근에 만든 조사의 진행도와 결과를 읽는다 — 화면이 조사 ID 없이 "분석 결과"를 열 때 쓴다.
     *
     * @return 진행도와 결과
     * @throws CustomException 조사가 하나도 없으면 {@code JOB_NOT_FOUND}
     */
    public JobResultResponse getLatest() {
        Job job = jobRepository.findFirstByOrderByJobIdDesc()
                .orElseThrow(() -> new CustomException(ErrorCode.JOB_NOT_FOUND));
        return JobResultResponse.of(job, withVerifications(taskService.findResults(job.getJobId())));
    }

    private List<TaskResultResponse> withVerifications(List<TaskResultResponse> tasks) {
        if (tasks.isEmpty()) {
            return tasks;
        }
        Map<Long, VerificationResponse> byTask = verificationService.findByTaskIds(
                tasks.stream().map(TaskResultResponse::taskId).toList());
        return tasks.stream()
                .map(task -> task.withVerification(byTask.get(task.taskId())))
                .toList();
    }
}
