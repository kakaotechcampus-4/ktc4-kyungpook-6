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

    @Enumerated(EnumType.STRING)
    @Column(name = "classification", nullable = false, length = 30)
    private TaskClassification classification;

    /** 조사 결과에 따른 수정안. 예: {"status": "CLOSED", "phone": "02-1234-5678"} */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "proposed_changes", columnDefinition = "jsonb")
    private Map<String, Object> proposedChanges;

    @Builder
    private Task(Job job, Store store, TaskClassification classification, Map<String, Object> proposedChanges) {
        this.job = job;
        this.store = store;
        this.classification = classification;
        this.proposedChanges = proposedChanges;
    }
}
