package com.ktc4.backend.domain.investigation.service;

import com.ktc4.backend.domain.investigation.client.AiClient;
import com.ktc4.backend.domain.investigation.client.AiContractError;
import com.ktc4.backend.domain.investigation.client.AiQuotaExceeded;
import com.ktc4.backend.domain.investigation.client.AiReadTimeout;
import com.ktc4.backend.domain.investigation.client.AiTransientError;
import com.ktc4.backend.domain.investigation.client.AiUnavailable;
import com.ktc4.backend.domain.investigation.dto.AiFinding;
import com.ktc4.backend.domain.investigation.dto.InvestigationPlan;
import com.ktc4.backend.domain.investigation.dto.InvestigationTarget;
import com.ktc4.backend.domain.investigation.dto.NtsTarget;
import com.ktc4.backend.domain.job.service.JobService;
import com.ktc4.backend.domain.task.service.TaskService;
import com.ktc4.backend.global.config.AsyncConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 조사 한 건을 뒤에서 처리한다 — 1차 대상의 수정안을 먼저 저장하고, AI 대상을 한 곳씩 조사해 저장한다.
 *
 * <p>트랜잭션을 걸지 않는다. 한 건이 수십 분이라 전체를 묶으면 그동안 커넥션을 잡고 중간 진행이 화면에 보이지 않는다.
 * 가게마다 {@link TaskService} 의 트랜잭션 하나로 결과와 진행 수를 함께 커밋한다.
 *
 * <p>실패 처리는 예외 타입으로 나눈다(멘토 Q4). {@link #processOne} 에서 잡는 예외는 그 가게만 실패로 남기고 계속하고,
 * 잡지 않는 예외(한도 초과·구현 없음)는 남은 가게도 다 같은 실패라 Job 을 멈춘다. 그 밖의 예상 못 한 예외도 바깥에서
 * 받아 Job 을 실패로 끝낸다 — 반환형이 void 인 {@code @Async} 는 예외를 로그 한 줄로 삼켜, 받지 않으면 Job 이 영원히
 * 진행 중으로 남는다.
 */
@Slf4j
@Service
public class InvestigationRunner {

    /** AI 일시 오류의 총 시도 횟수(첫 시도 포함). 멘토 Q4 의 {@code MAX_ATTEMPTS = 2} 와 같다. */
    static final int MAX_ATTEMPTS = 2;

    // 화면에 나가는 실패 이유. 예외 메시지 원문(내부 주소·응답 일부가 섞일 수 있음)은 로그에만 남긴다.
    static final String REASON_AI_FAILED = "AI 가 이 가게를 조사하지 못했습니다";
    static final String REASON_TIMEOUT = "AI 응답 시간이 초과됐습니다";
    static final String REASON_CONTRACT = "AI 응답을 해석하지 못했습니다";
    static final String REASON_TRANSIENT = "AI 서버에 일시적인 문제가 있었습니다";
    static final String REASON_SAVE_FAILED = "조사 결과를 저장하지 못했습니다";
    static final String ERROR_QUOTA = "AI 사용량 한도를 넘어 조사를 멈췄습니다";
    static final String ERROR_UNAVAILABLE = "AI 조사를 쓸 수 없어 조사를 멈췄습니다";
    static final String ERROR_INTERNAL = "서버 오류로 조사를 멈췄습니다";

    private final AiClient aiClient;
    private final JobService jobService;
    private final TaskService taskService;
    private final long retryBackoffMs;

    @Autowired
    public InvestigationRunner(AiClient aiClient, JobService jobService, TaskService taskService,
                               @Value("${ai.retry-backoff-ms}") long retryBackoffMs) {
        this.aiClient = aiClient;
        this.jobService = jobService;
        this.taskService = taskService;
        this.retryBackoffMs = retryBackoffMs;
    }

    /**
     * 조사를 뒤에서 실행한다. 부른 쪽은 기다리지 않고 바로 돌아간다.
     *
     * <p>조사를 만든 트랜잭션이 커밋된 뒤에 불러야 한다 — 이 메서드는 다른 스레드에서 Job 을 다시 읽는다.
     * RuntimeException 은 밖으로 던지지 않는다. Error 는 Job 을 실패로 끝내 둔 뒤 다시 던진다.
     * 어떤 경우든 Job 을 완료나 실패로 끝내려 하고, 그마저 못 하면 서버 재시작 정리에 맡긴다.
     *
     * @param plan 실행할 조사 (1차 대상, AI 대상)
     */
    @Async(AsyncConfig.INVESTIGATION_EXECUTOR)
    public void run(InvestigationPlan plan) {
        Long jobId = plan.jobId();
        try {
            jobService.start(jobId);
            for (NtsTarget target : plan.ntsTargets()) {
                checkInterrupted();
                saveNts(jobId, target);
            }
            for (InvestigationTarget target : plan.aiTargets()) {
                checkInterrupted();
                processOne(jobId, target);
            }
            jobService.finish(jobId, LocalDateTime.now());
            log.info("조사 완료 - jobId={}, {}곳", jobId, plan.targetCount());
        } catch (Interrupted e) {
            // 서버가 꺼지는 중이다. Job 은 진행 중으로 남고, 다시 켜질 때 정리된다.
            log.warn("조사 중단(인터럽트) - jobId={}", jobId);
        } catch (AiQuotaExceeded | AiUnavailable e) {
            log.warn("조사 중단 - jobId={}, 이유={}", jobId, e.getMessage());
            failJob(jobId, e instanceof AiQuotaExceeded ? ERROR_QUOTA : ERROR_UNAVAILABLE);
        } catch (RuntimeException e) {
            log.error("조사 중 예상 못 한 오류 - jobId={}", jobId, e);
            failJob(jobId, ERROR_INTERNAL);
        } catch (Error e) {
            // StackOverflowError 같은 Error 도 Job 을 진행 중으로 남기지 않게 끝내 두고, 삼키지 않고 다시 던진다
            log.error("조사 중 치명적 오류 - jobId={}", jobId, e);
            failJob(jobId, ERROR_INTERNAL);
            throw e;
        }
    }

    private void saveNts(Long jobId, NtsTarget target) {
        try {
            taskService.saveNts(jobId, target);
        } catch (RuntimeException e) {
            log.error("1차 결과 저장 실패 - jobId={}, storeId={}", jobId, target.storeId(), e);
            // 실패 기록마저 실패하면 위로 올라가 안전망이 Job 을 끝낸다 — 그 가게를 빠뜨린 채 계속 가지 않는다
            taskService.saveFailure(jobId, target.storeId(), REASON_SAVE_FAILED);
        }
    }

    // 여기서 잡는 예외 = 그 가게만 실패로 남기고 계속할 것, 안 잡는 예외 = Job 을 멈출 것 (멘토 Q4)
    private void processOne(Long jobId, InvestigationTarget target) {
        Long storeId = target.storeId();
        AiFinding finding;
        try {
            finding = investigateWithRetry(target);
        } catch (AiReadTimeout e) {
            recordFailure(jobId, storeId, REASON_TIMEOUT, e);
            return;
        } catch (AiContractError e) {
            recordFailure(jobId, storeId, REASON_CONTRACT, e);
            return;
        } catch (AiTransientError e) {
            recordFailure(jobId, storeId, REASON_TRANSIENT, e);
            return;
        }

        if (finding.isFailed()) {
            // AI 가 이미 판단을 끝낸 실패다. 다시 부르면 또 60초를 쓴다.
            log.info("AI 가 가게를 조사하지 못함 - jobId={}, storeId={}, 이유={}", jobId, storeId, finding.failure());
            taskService.saveFailure(jobId, storeId, REASON_AI_FAILED);
            return;
        }
        try {
            taskService.save(jobId, finding, LocalDateTime.now());
        } catch (RuntimeException e) {
            log.error("AI 결과 저장 실패 - jobId={}, storeId={}", jobId, storeId, e);
            taskService.saveFailure(jobId, storeId, REASON_SAVE_FAILED);
        }
    }

    private AiFinding investigateWithRetry(InvestigationTarget target) {
        for (int attempt = 1; ; attempt++) {
            checkInterrupted();
            try {
                return aiClient.investigate(target);
            } catch (AiTransientError e) {
                if (attempt >= MAX_ATTEMPTS) {
                    throw e;
                }
                log.warn("AI 호출 실패 {}/{} (storeId={}): {}", attempt, MAX_ATTEMPTS, target.storeId(), e.getMessage());
                sleep(retryBackoffMs * attempt);
            }
        }
    }

    // 마지막 시도 도중 인터럽트되면 JDK 클라이언트가 그것을 I/O 실패로 감싸 보낸다 — 서버 종료를 그 가게의 실패로 남기지 않게 먼저 본다
    private void recordFailure(Long jobId, Long storeId, String reason, RuntimeException cause) {
        checkInterrupted();
        log.warn("AI 조사 실패 - jobId={}, storeId={}, 종류={}, 내용={}",
                jobId, storeId, cause.getClass().getSimpleName(), cause.getMessage());
        taskService.saveFailure(jobId, storeId, reason);
    }

    private void failJob(Long jobId, String errorMessage) {
        try {
            jobService.fail(jobId, errorMessage, LocalDateTime.now());
        } catch (RuntimeException e) {
            log.error("조사를 실패로 기록하지 못함 - jobId={} (서버 재시작 때 정리됨)", jobId, e);
        }
    }

    // JDK HTTP 클라이언트는 인터럽트를 IOException 으로 감싸 일시 오류처럼 보이게 하므로, 재시도 전에 플래그를 먼저 본다.
    private static void checkInterrupted() {
        if (Thread.currentThread().isInterrupted()) {
            throw new Interrupted();
        }
    }

    private static void sleep(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Interrupted();
        }
    }

    /** 서버 종료로 실행기 스레드가 인터럽트됨. 실행기 밖으로 나가지 않는다. */
    private static final class Interrupted extends RuntimeException {
        private Interrupted() {
            super(null, null, false, false);
        }
    }
}
