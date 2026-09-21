package com.ktc4.backend.domain.signal.entity;

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

    /**
     * 신뢰도 (0.0 ~ 1.0). {@code Double}(래퍼 타입)인 이유: {@code double}(primitive)이면 빌더 호출 시
     * 값을 빠뜨려도 컴파일·실행이 다 되고 기본값 0.0이 조용히 들어가 버린다("신뢰도 0.0"은 실수인지
     * 진짜 값인지 구분이 안 되는 값이라 특히 위험함). {@code Double}로 두면 값이 빠졌을 때 DB의
     * {@code nullable = false} 제약에서 저장 시점에 확실히 걸러진다.
     */
    @Column(name = "confidence", nullable = false)
    private Double confidence;

    @Column(name = "evidence_text", columnDefinition = "TEXT")
    private String evidenceText;

    @Column(name = "evidence_url", length = 2048)
    private String evidenceUrl;

    @Builder
    private Signal(Task task, SignalType signalType, Double confidence, String evidenceText, String evidenceUrl) {
        if (confidence != null && (confidence < 0.0 || confidence > 1.0)) {
            throw new CustomException(ErrorCode.INVALID_REQUEST);
        }
        this.task = task;
        this.signalType = signalType;
        this.confidence = confidence;
        this.evidenceText = evidenceText;
        this.evidenceUrl = evidenceUrl;
    }
}
