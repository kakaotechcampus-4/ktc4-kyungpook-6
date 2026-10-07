package com.ktc4.backend.domain.job.service;

import com.ktc4.backend.domain.investigation.dto.InvestigationPlan;
import com.ktc4.backend.domain.investigation.dto.InvestigationTarget;
import com.ktc4.backend.domain.investigation.dto.NtsTarget;
import com.ktc4.backend.domain.job.dto.CreatedJob;
import com.ktc4.backend.domain.job.entity.Job;
import com.ktc4.backend.domain.job.enums.JobStatus;
import com.ktc4.backend.domain.job.repository.JobRepository;
import com.ktc4.backend.domain.store.dto.InvestigationTargets;
import com.ktc4.backend.domain.store.service.InvestigationTargetSelector;
import com.ktc4.backend.global.error.CustomException;
import com.ktc4.backend.global.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 조사(Job)를 만들고 상태를 바꾼다.
 *
 * <p>조사 실행은 {@code InvestigationRunner} 가 다른 스레드에서 하고, 이 서비스는 각 단계를 짧은 트랜잭션으로 끊어
 * 커밋한다 — 실행기 전체를 트랜잭션 하나로 묶으면 수십 분 동안 커넥션을 잡고, 중간 진행이 화면에 보이지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class JobService {

    /** 한 번에 조사할 수 있는 가게 수(중복을 뺀 뒤). 가게마다 AI 가 수십 초를 쓰므로 한 번에 너무 많이 고르지 않게 한다. */
    public static final int MAX_STORES = 100;

    private final JobRepository jobRepository;
    private final InvestigationTargetSelector targetSelector;

    /**
     * 담당자가 고른 가게로 조사를 만든다. 조사는 시작하지 않는다 — 이 트랜잭션이 커밋된 뒤 호출하는 쪽이
     * 실행기에 {@link CreatedJob#plan()} 을 넘긴다.
     *
     * <p>가게는 1차 조사(국세청 대조) 결과로 나눈다({@link InvestigationTargetSelector}). 국세청과 상태가 다른 가게는
     * AI 없이 1차 수정안으로 끝나고, 같은 가게만 AI 로 간다. 대상 수는 둘을 합친 수다.
     *
     * @param storeIds    담당자가 고른 가게 ID. 같은 ID 가 여러 번 와도 한 번만 다룬다
     * @param requestedBy 조사를 시작한 관리자의 회원 ID
     * @return 만든 조사와 실행기에 넘길 할 일
     * @throws CustomException 중복을 뺀 가게가 {@value #MAX_STORES}곳을 넘으면 {@code INVALID_REQUEST},
     *                         제외하고 나니 조사할 가게가 없으면 {@code NO_INVESTIGATION_TARGET}
     */
    @Transactional
    public CreatedJob create(List<Long> storeIds, Long requestedBy) {
        List<Long> distinctIds = storeIds.stream().distinct().toList();
        if (distinctIds.size() > MAX_STORES) {
            log.warn("조사 요청 가게 수가 최대치를 넘어 거부함 - 요청 {}곳, 최대 {}곳", distinctIds.size(), MAX_STORES);
            throw new CustomException(ErrorCode.INVALID_REQUEST);
        }

        InvestigationTargets targets = targetSelector.select(distinctIds);
        List<NtsTarget> ntsTargets = targets.resolvedByNts().stream().map(NtsTarget::from).toList();
        List<InvestigationTarget> aiTargets = targets.aiTargets().stream().map(InvestigationTarget::from).toList();
        int targetCount = ntsTargets.size() + aiTargets.size();
        if (targetCount == 0) {
            throw new CustomException(ErrorCode.NO_INVESTIGATION_TARGET);
        }

        Job job = jobRepository.save(Job.builder()
                .requestedBy(String.valueOf(requestedBy))
                .status(JobStatus.PENDING)
                .targetCount(targetCount)
                .completedCount(0)
                .build());
        log.info("조사 생성 - jobId={}, 1차 {}곳, AI {}곳, 제외 {}곳",
                job.getJobId(), ntsTargets.size(), aiTargets.size(), targets.excluded().size());

        InvestigationPlan plan = new InvestigationPlan(job.getJobId(), ntsTargets, aiTargets);
        return new CreatedJob(job.getJobId(), targetCount, targets.excluded(), plan);
    }

    /**
     * 조사를 진행 중으로 바꾼다.
     *
     * @param jobId 조사 ID
     * @throws CustomException 조사가 없으면 {@code JOB_NOT_FOUND}
     * @throws IllegalStateException 대기 중이 아니면 (이미 시작했거나 재시작 정리로 끝남)
     */
    @Transactional
    public void start(Long jobId) {
        findJob(jobId).start();
    }

    /**
     * 가게 한 곳이 끝났음을 센다. 그 가게의 Task 를 저장하는 트랜잭션 안에서 불러, 저장과 진행 수가 함께 커밋되게 한다.
     *
     * @param jobId 조사 ID
     * @return 진행 수를 올린 조사 — 같은 트랜잭션에서 Task 가 참조한다
     * @throws CustomException 조사가 없으면 {@code JOB_NOT_FOUND}
     * @throws IllegalStateException 진행 중이 아니거나 대상 수만큼 이미 셌으면
     */
    @Transactional
    public Job recordProgress(Long jobId) {
        Job job = findJob(jobId);
        job.recordProgress();
        return job;
    }

    /**
     * 조사를 완료로 끝낸다.
     *
     * @param jobId      조사 ID
     * @param finishedAt 끝난 시각
     * @throws CustomException 조사가 없으면 {@code JOB_NOT_FOUND}
     * @throws IllegalStateException 진행 중이 아니면
     */
    @Transactional
    public void finish(Long jobId, LocalDateTime finishedAt) {
        findJob(jobId).finish(finishedAt);
    }

    /**
     * 조사를 실패로 끝낸다. 그때까지 저장한 결과와 진행 수는 남는다.
     *
     * @param jobId        조사 ID
     * @param errorMessage 화면에 보여 줄 실패 이유 (정해 둔 문구)
     * @param failedAt     끝난 시각
     * @throws CustomException 조사가 없으면 {@code JOB_NOT_FOUND}
     * @throws IllegalStateException 이미 끝난 조사면
     */
    @Transactional
    public void fail(Long jobId, String errorMessage, LocalDateTime failedAt) {
        findJob(jobId).fail(errorMessage, failedAt);
    }

    /**
     * 서버가 다시 켜졌을 때, 그 전에 시작해 끝나지 못한 조사를 실패로 정리한다.
     *
     * <p>실행기의 대기열은 메모리에만 있어서 서버가 꺼지면 대기 중이던 조사도 다시는 돌지 않는다. 그대로 두면
     * 영원히 진행 중으로 남아 화면이 끝없이 폴링한다. 기동 이후에 만든 조사는 건드리지 않는다 — 웹 서버가 이 정리보다
     * 먼저 요청을 받을 수 있기 때문이다.
     *
     * @param bootedAt     이 프로세스가 기동한 시각. 이보다 먼저 만든 조사만 정리한다
     * @param errorMessage 정리한 조사에 남길 실패 이유
     * @param failedAt     실패로 기록할 시각
     * @return 정리한 조사 수
     */
    @Transactional
    public int failUnfinishedBefore(LocalDateTime bootedAt, String errorMessage, LocalDateTime failedAt) {
        return jobRepository.failUnfinishedCreatedBefore(
                List.of(JobStatus.PENDING, JobStatus.IN_PROGRESS), bootedAt, errorMessage, failedAt);
    }

    private Job findJob(Long jobId) {
        return jobRepository.findById(jobId)
                .orElseThrow(() -> new CustomException(ErrorCode.JOB_NOT_FOUND));
    }
}
