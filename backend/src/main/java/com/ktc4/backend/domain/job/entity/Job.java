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
 * <p>상태 전이(예: PENDING → IN_PROGRESS → DONE)와 진행률 갱신 메서드는 아직 넣지 않았다 —
 * 그 로직을 실제로 어떤 흐름(배치? 이벤트?)으로 실행할지가 아직 정해지지 않아서, 지금 미리 만들면
 * 스펙 없는 API를 추측해서 만드는 셈이 된다. 조사 실행 로직을 만드는 티켓에서 이 엔티티에
 * 상태 전이 메서드를 추가하면 된다.
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
}
