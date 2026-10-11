package com.ktc4.backend.domain.task.entity;

import com.ktc4.backend.domain.job.entity.Job;
import com.ktc4.backend.domain.store.entity.Store;
import com.ktc4.backend.domain.task.enums.TaskClassification;
import com.ktc4.backend.global.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.Map;

/**
 * Job 안에서 특정 Store 하나를 조사하는 작업 단위.
 *
 * <p>{@code job} / {@code store}는 조회(조인) 목적으로만 참조한다 — Job/Store 자체의 필드를
 * 바꿔야 할 땐 이 클래스에서 직접 손대지 않고 각자의 Service를 거친다.
 */
@Entity
@Table(
        name = "task",
        indexes = {
                @Index(name = "idx_task_job_id", columnList = "job_id"),
                @Index(name = "idx_task_store_id", columnList = "store_id")
        }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Task extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "task_id")
    private Long taskId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "job_id", nullable = false)
    private Job job;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "store_id", nullable = false)
    private Store store;

    /**
     * 조사 판정. 조사에 실패하면 판정이 없어 비어 있고, 대신 {@link #failureReason} 이 채워진다 —
     * AI 가 실패를 나타내는 방식({@code classification = None} + {@code failure})과 같다.
     *
     * <p>⚠️ 원래 NOT NULL 이던 컬럼이다. ddl-auto=update 는 기존 컬럼의 NOT NULL 을 풀지 않으므로, 이미 테이블이 있는
     * DB 에는 {@code ALTER TABLE task ALTER COLUMN classification DROP NOT NULL} 을 직접 실행해야 한다 — 안 하면
     * 실패 Task 저장이 모두 막혀 조사가 "서버 오류"로 멈춘다.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "classification", length = 30)
    private TaskClassification classification;

    /** 조사에 실패한 이유. 화면에 나가므로 예외 메시지 원문이 아니라 정해 둔 문구를 담는다. 성공이면 비어 있다. */
    @Column(name = "failure_reason", length = 500)
    private String failureReason;

    /** 조사 결과에 따른 수정안. 예: {"status": "CLOSED", "phone": "02-1234-5678"} */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "proposed_changes", columnDefinition = "jsonb")
    private Map<String, Object> proposedChanges;

    @Builder
    private Task(Job job, Store store, TaskClassification classification, Map<String, Object> proposedChanges,
                 String failureReason) {
        // 판정과 실패 이유는 정확히 하나만 있어야 한다 — 둘 다 없거나 둘 다 있으면 화면이 어느 섹션에 넣을지 모른다.
        if ((classification == null) == (failureReason == null)) {
            throw new IllegalArgumentException("Task 는 판정(classification)과 실패 이유(failureReason) 중 정확히 하나를 가져야 합니다");
        }
        this.job = job;
        this.store = store;
        this.classification = classification;
        this.proposedChanges = proposedChanges;
        this.failureReason = failureReason;
    }

    /** 조사에 실패한 Task 인가 — 판정 없이 실패 이유만 있다. */
    public boolean isFailed() {
        return failureReason != null;
    }
}
