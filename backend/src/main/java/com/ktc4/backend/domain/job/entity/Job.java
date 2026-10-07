package com.ktc4.backend.domain.job.entity;

import com.ktc4.backend.domain.job.enums.JobStatus;
import com.ktc4.backend.global.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 담당자가 실행한 "가게 정보 일괄 조사" 작업 단위.
 *
 * <p>실제 조사 대상 하나하나는 {@code Task} 로 나뉘고, Job 은 그 Task 들을 묶는 배치 실행 기록이다.
 *
 * <p>상태는 {@code PENDING → IN_PROGRESS → DONE} 으로 가고, 중간에 멈추면 {@code FAILED} 다. 전이는
 * {@code InvestigationRunner} 가 {@code JobService} 를 거쳐서만 하고, 순서가 어긋난 호출은
 * {@link IllegalStateException} 으로 막는다 — 실행기와 서버 재시작 정리가 같은 Job 을 건드릴 수 있어서다.
 * 재시작 정리는 엔티티 메서드가 아니라 벌크 쿼리({@code JobRepository.failUnfinishedCreatedBefore})로 한다.
 */
@Entity
@Table(
        name = "job",
        indexes = {
                @Index(name = "idx_job_status", columnList = "status")
        }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Job extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "job_id")
    private Long jobId;

    /** 조사를 실행한 담당자 */
    @Column(name = "requested_by", nullable = false, length = 100)
    private String requestedBy;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private JobStatus status;

    /** 조사 대상 가게 수 */
    @Column(name = "target_count", nullable = false)
    private int targetCount;

    /** 조사 완료된 가게 수 */
    @Column(name = "completed_count", nullable = false)
    private int completedCount;

    /** 조사 완료 시각 (진행 중이면 null) */
    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    /** 실패 사유 (성공했거나 아직 진행 중이면 null) */
    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Builder
    private Job(String requestedBy, JobStatus status,
                int targetCount, int completedCount,
                LocalDateTime finishedAt, String errorMessage) {
        this.requestedBy = requestedBy;
        this.status = status;
        this.targetCount = targetCount;
        this.completedCount = completedCount;
        this.finishedAt = finishedAt;
        this.errorMessage = errorMessage;
    }

    /**
     * 조사를 시작한다. 대기 중인 Job 만 시작할 수 있다.
     *
     * @throws IllegalStateException 대기 중이 아닐 때 — 같은 Job 을 두 번 돌리려 했다는 뜻이다
     */
    public void start() {
        requireStatus(JobStatus.PENDING);
        this.status = JobStatus.IN_PROGRESS;
    }

    /**
     * 가게 한 곳의 조사가 끝났음을 센다. 성공·실패 모두 한 건이다.
     *
     * @throws IllegalStateException 진행 중이 아니거나, 이미 대상 수만큼 셌을 때(같은 가게를 두 번 셌다는 뜻)
     */
    public void recordProgress() {
        requireStatus(JobStatus.IN_PROGRESS);
        if (completedCount >= targetCount) {
            throw new IllegalStateException(
                    "완료 수가 대상 수를 넘을 수 없습니다 - jobId=" + jobId + ", targetCount=" + targetCount);
        }
        this.completedCount++;
    }

    /**
     * 조사를 완료로 끝낸다.
     *
     * <p>시각은 호출자가 넘긴다 — 엔티티가 직접 {@code LocalDateTime.now()} 를 부르면 테스트에서 시각을 통제할 수 없다.
     *
     * @param finishedAt 끝난 시각
     * @throws IllegalStateException 진행 중이 아닐 때
     */
    public void finish(LocalDateTime finishedAt) {
        requireStatus(JobStatus.IN_PROGRESS);
        this.status = JobStatus.DONE;
        this.finishedAt = finishedAt;
    }

    /**
     * 조사를 실패로 끝낸다. 그때까지 끝난 가게 수({@code completedCount})와 저장된 결과는 그대로 남는다.
     *
     * <p>{@code DONE} 은 "끝났다"는 뜻이라, 담당자가 다시 돌려야 하는 중단은 {@code FAILED} 로 드러낸다.
     *
     * @param errorMessage 화면에 보여 줄 실패 이유 — 예외 메시지 원문이 아니라 정해 둔 문구
     * @param failedAt     끝난 시각
     * @throws IllegalStateException 이미 끝난 Job 일 때 — 늦게 온 실패가 완료 결과를 덮어쓰지 않게 한다
     */
    public void fail(String errorMessage, LocalDateTime failedAt) {
        if (isFinished()) {
            throw new IllegalStateException("이미 끝난 Job 입니다 - jobId=" + jobId + ", status=" + status);
        }
        this.status = JobStatus.FAILED;
        this.errorMessage = errorMessage;
        this.finishedAt = failedAt;
    }

    /** 완료나 실패로 끝났는가. */
    public boolean isFinished() {
        return status == JobStatus.DONE || status == JobStatus.FAILED;
    }

    private void requireStatus(JobStatus expected) {
        if (status != expected) {
            throw new IllegalStateException(
                    "Job 상태가 " + expected + " 가 아닙니다 - jobId=" + jobId + ", status=" + status);
        }
    }
}
