package com.ktc4.backend.domain.store.ntscheck.entity;

import com.ktc4.backend.domain.business.enums.BusinessState;
import com.ktc4.backend.domain.store.entity.Store;
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

import java.time.LocalDateTime;

/**
 * 국세청 상태가 이전과 달라진 순간만 남기는 이력. 한 번 쌓으면 고치지 않는다.
 *
 * <p>{@link StoreNtsCheck} 는 최신 상태 한 줄만 들고 있어 "언제 바뀌었는지"를 알 수 없다.
 * 그 답이 필요한 곳(폐업 전환 추적 등)을 위해 변화가 생긴 시점만 따로 적는다.
 * 조회 실패({@code UNCONFIRMED})는 상태가 바뀐 게 아니라 못 본 것이므로 이력에 남기지 않는다.
 *
 * <p>{@code fromState} 는 그 가게의 국세청 상태를 처음 확인한 경우 비어 있다.
 *
 * <p>{@code detectedAt} 은 "배치가 변화를 발견한 시각"이라는 도메인 의미를 가진 값이라,
 * 행 생성 시각을 기록하는 {@code BaseTimeEntity} 의 createdAt 과는 별개로 관리한다
 * ({@code Verification.verifiedAt} 과 같은 이유). 국세청이 실제로 상태를 바꾼 시각은 알 수 없다.
 */
@Entity
@Table(
        name = "store_nts_change",
        indexes = {
                @Index(name = "idx_store_nts_change_store_id", columnList = "store_id")
        }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StoreNtsChange extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "store_nts_change_id")
    private Long storeNtsChangeId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "store_id", nullable = false)
    private Store store;

    /** 바뀌기 전 국세청 상태. 처음 확인한 경우 null. */
    @Enumerated(EnumType.STRING)
    @Column(name = "from_state", length = 20)
    private BusinessState fromState;

    /** 바뀐 뒤 국세청 상태 */
    @Enumerated(EnumType.STRING)
    @Column(name = "to_state", nullable = false, length = 20)
    private BusinessState toState;

    /** 배치가 이 변화를 발견한 시각 */
    @Column(name = "detected_at", nullable = false)
    private LocalDateTime detectedAt;

    @Builder
    private StoreNtsChange(Store store, BusinessState fromState, BusinessState toState, LocalDateTime detectedAt) {
        this.store = store;
        this.fromState = fromState;
        this.toState = toState;
        this.detectedAt = detectedAt;
    }
}
