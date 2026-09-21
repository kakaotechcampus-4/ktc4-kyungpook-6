package com.ktc4.backend.domain.verification.entity;

import com.ktc4.backend.domain.store.entity.Store;
import com.ktc4.backend.domain.task.entity.Task;
import com.ktc4.backend.domain.verification.enums.VerificationAction;
import com.ktc4.backend.domain.verification.enums.VerificationResult;
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

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 담당자가 가게를 실제로 확인한 기록.
 *
 * <p>{@code task}는 Task 조사 결과를 보고 확인한 경우엔 채워지고, 담당자가 Task 없이 직접
 * 수동으로 확인한 경우엔 비워둘 수 있다(nullable).
 *
 * <p>{@code verifiedAt}은 "실제로 확인한 시각"이라는 도메인 의미를 가진 값이라, 행 생성 시각을
 * 기록하는 {@code BaseTimeEntity}의 createdAt과는 별개로 관리한다 (Store의 lastCheckedAt과 같은 이유).
 */
@Entity
@Table(
        name = "verification",
        indexes = {
                @Index(name = "idx_verification_store_id", columnList = "store_id"),
                @Index(name = "idx_verification_task_id", columnList = "task_id")
        }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Verification extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "verification_id")
    private Long verificationId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "store_id", nullable = false)
    private Store store;

    /** 확인 대상 Task. Task 조사 결과 없이 담당자가 직접 확인한 경우 null. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "task_id")
    private Task task;

    @Enumerated(EnumType.STRING)
    @Column(name = "result", nullable = false, length = 30)
    private VerificationResult result;

    @Enumerated(EnumType.STRING)
    @Column(name = "action", nullable = false, length = 30)
    private VerificationAction action;

    /** 실제로 적용된 수정 내용. 예: {"status": "CLOSED"} */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "applied_changes", columnDefinition = "jsonb")
    private Map<String, Object> appliedChanges;

    @Column(name = "note", columnDefinition = "TEXT")
    private String note;

    @Column(name = "verified_by", nullable = false, length = 100)
    private String verifiedBy;

    @Column(name = "verified_at", nullable = false)
    private LocalDateTime verifiedAt;

    @Builder
    private Verification(Store store, Task task, VerificationResult result, VerificationAction action,
                          Map<String, Object> appliedChanges, String note,
                          String verifiedBy, LocalDateTime verifiedAt) {
        this.store = store;
        this.task = task;
        this.result = result;
        this.action = action;
        this.appliedChanges = appliedChanges;
        this.note = note;
        this.verifiedBy = verifiedBy;
        this.verifiedAt = verifiedAt;
    }
}
