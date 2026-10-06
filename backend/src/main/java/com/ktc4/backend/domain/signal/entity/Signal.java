package com.ktc4.backend.domain.signal.entity;

import com.ktc4.backend.domain.signal.enums.ChangeField;
import com.ktc4.backend.domain.signal.enums.SignalSource;
import com.ktc4.backend.domain.signal.enums.SignalType;
import com.ktc4.backend.domain.task.entity.Task;
import com.ktc4.backend.global.entity.BaseTimeEntity;
import com.ktc4.backend.global.error.CustomException;
import com.ktc4.backend.global.error.ErrorCode;
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

/**
 * Task를 조사하는 과정에서 발견된 개별 이상 징후 하나.
 */
@Entity
@Table(
        name = "signal",
        indexes = {
                @Index(name = "idx_signal_task_id", columnList = "task_id")
        }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Signal extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "signal_id")
    private Long signalId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "task_id", nullable = false)
    private Task task;

    @Enumerated(EnumType.STRING)
    @Column(name = "signal_type", nullable = false, length = 50)
    private SignalType signalType;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 30)
    private SignalSource source;

    @Enumerated(EnumType.STRING)
    @Column(name = "field", nullable = false, length = 30)
    private ChangeField field;

    /**
     * 이 출처가 본 새 값. 상태면 {@code StoreStatus} 이름(예: {@code CLOSED}), 그 밖은 원문.
     * {@code Task.proposedChanges} 는 항목마다 최종 제안 하나만 담아서, 출처끼리 값이 다를 때
     * 각 출처가 무엇을 봤는지는 여기에만 남는다. 길이는 가장 긴 항목인 {@code Store.addressRoad}(500)에 맞춘다.
     */
    @Column(name = "observed", length = 500)
    private String observed;

    /**
     * 신뢰도 (0.0 ~ 1.0). 없을 수 있다 — AI 는 모델이 매긴 확신도를 판단 근거로 쓰지 않기로 해서 보내지 않는다.
     * 값이 있을 때만 범위를 검사한다.
     */
    @Column(name = "confidence")
    private Double confidence;

    @Column(name = "evidence_text", columnDefinition = "TEXT")
    private String evidenceText;

    @Column(name = "evidence_url", length = 2048)
    private String evidenceUrl;

    @Builder
    private Signal(Task task, SignalType signalType, SignalSource source, ChangeField field,
                   String observed, Double confidence, String evidenceText, String evidenceUrl) {
        if (confidence != null && (confidence < 0.0 || confidence > 1.0)) {
            throw new CustomException(ErrorCode.INVALID_REQUEST);
        }
        this.task = task;
        this.signalType = signalType;
        this.source = source;
        this.field = field;
        this.observed = observed;
        this.confidence = confidence;
        this.evidenceText = evidenceText;
        this.evidenceUrl = evidenceUrl;
    }
}
