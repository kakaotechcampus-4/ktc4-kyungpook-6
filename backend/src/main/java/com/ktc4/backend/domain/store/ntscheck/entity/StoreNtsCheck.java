package com.ktc4.backend.domain.store.ntscheck.entity;

import com.ktc4.backend.domain.business.enums.BusinessState;
import com.ktc4.backend.domain.store.entity.Store;
import com.ktc4.backend.domain.store.enums.NtsLookupResult;
import com.ktc4.backend.global.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 가게 한 곳의 국세청 조회 결과 중 <b>가장 최근 것</b>만 담는 기록. 가게당 한 행이다.
 *
 * <p>가게의 실제 영업 상태({@code Store.status})나 검증 시각({@code Store.lastCheckedAt})은 이
 * 배치가 건드리지 않는다. 국세청의 폐업은 사업자 기준이라 실제 매장 상태와 다를 수 있어서,
 * 자동으로 가게 정보를 바꾸지 않고 여기에만 따로 쌓아 둔다.
 *
 * <p>{@code checkResult} 와 {@code ntsState} 는 층이 다른 값이다. 앞은 "조회가 어떻게 끝났는가",
 * 뒤는 "마지막으로 확인된 국세청 상태"다. 조회에 실패해도 뒤엣값은 지우지 않는다 — 한 번 실패했다고
 * 예전에 확인해 둔 사실까지 잃으면, 화면에서 "폐업이었는데 지금은 모른다"와 "원래부터 모른다"를
 * 구분할 수 없게 된다.
 *
 * <p>PK 는 {@code store_id} 하나이며 동시에 store 테이블을 가리키는 FK 다({@code @MapsId} 공유 PK).
 * 별도 식별자를 두면 "가게당 한 행"을 DB 가 보장해 주지 못한다.
 */
@Entity
@Table(
        name = "store_nts_check",
        indexes = {
                @Index(name = "idx_store_nts_check_check_result", columnList = "check_result")
        }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StoreNtsCheck extends BaseTimeEntity {

    @Id
    @Column(name = "store_id")
    private Long storeId;

    @MapsId
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "store_id")
    private Store store;

    /** 마지막 조회에 실제로 사용한 사업자등록번호(하이픈 없는 숫자). 한 번도 조회한 적 없으면 null. */
    @Column(name = "biz_no", length = 20)
    private String bizNo;

    /** 마지막 조회가 어떻게 끝났는지 */
    @Enumerated(EnumType.STRING)
    @Column(name = "check_result", nullable = false, length = 20)
    private NtsLookupResult checkResult;

    /** 마지막으로 <b>확인에 성공한</b> 국세청 상태. 조회 실패 시에도 지우지 않는다. */
    @Enumerated(EnumType.STRING)
    @Column(name = "nts_state", length = 20)
    private BusinessState ntsState;

    /** 국세청 기준 폐업일. 폐업자일 때만 채워진다. */
    @Column(name = "nts_closed_at")
    private LocalDate ntsClosedAt;

    /** 배치가 이 가게를 마지막으로 처리한 시각. 조회를 건너뛴 경우(NO_BIZ_NO)에도 갱신된다. */
    @Column(name = "last_attempt_at", nullable = false)
    private LocalDateTime lastAttemptAt;

    /** 마지막으로 국세청 상태를 확인하는 데 성공한 시각. 성공한 적이 없으면 null. */
    @Column(name = "last_success_at")
    private LocalDateTime lastSuccessAt;

    @Builder
    private StoreNtsCheck(Store store, String bizNo, NtsLookupResult checkResult,
                          BusinessState ntsState, LocalDate ntsClosedAt,
                          LocalDateTime lastAttemptAt, LocalDateTime lastSuccessAt) {
        this.store = store;
        this.bizNo = bizNo;
        this.checkResult = checkResult;
        this.ntsState = ntsState;
        this.ntsClosedAt = ntsClosedAt;
        this.lastAttemptAt = lastAttemptAt;
        this.lastSuccessAt = lastSuccessAt;
    }

    /**
     * 아직 한 번도 조회하지 않은 가게의 빈 기록을 만든다.
     *
     * <p>필수값({@code checkResult}, {@code lastAttemptAt})은 곧이어 호출하는 {@code applyXxx} 가
     * 채운다. 결과별로 채우는 칸이 달라, 만드는 쪽과 갱신하는 쪽을 한 갈래로 합치기 위한 방식이다.
     *
     * @param store 대상 가게
     * @return 아직 아무 결과도 담기지 않은 기록
     */
    public static StoreNtsCheck initial(Store store) {
        return StoreNtsCheck.builder().store(store).build();
    }

    /**
     * 국세청 상태를 확인한 결과로 갱신한다.
     *
     * @param bizNo     조회에 사용한 사업자등록번호
     * @param state     확인된 국세청 상태
     * @param closedAt  국세청 기준 폐업일 (폐업이 아니면 null)
     * @param checkedAt 조회 시각
     */
    public void applyConfirmed(String bizNo, BusinessState state, LocalDate closedAt, LocalDateTime checkedAt) {
        this.bizNo = bizNo;
        this.checkResult = NtsLookupResult.CONFIRMED;
        this.ntsState = state;
        this.ntsClosedAt = closedAt;
        this.lastAttemptAt = checkedAt;
        this.lastSuccessAt = checkedAt;
    }

    /**
     * 조회는 했지만 상태를 확인하지 못한 결과로 갱신한다.
     *
     * <p>{@code ntsState} / {@code ntsClosedAt} / {@code lastSuccessAt} 은 그대로 둔다.
     *
     * @param bizNo     조회에 사용한 사업자등록번호
     * @param checkedAt 조회 시각
     */
    public void applyUnconfirmed(String bizNo, LocalDateTime checkedAt) {
        this.bizNo = bizNo;
        this.checkResult = NtsLookupResult.UNCONFIRMED;
        this.lastAttemptAt = checkedAt;
    }

    /**
     * 사업자등록번호가 없거나 형식이 틀려 조회하지 않았다는 결과로 갱신한다.
     *
     * <p>조회를 아예 하지 않았으므로 국세청 관련 값은 하나도 건드리지 않는다. 예전에 번호가
     * 있었다가 지워진 경우 마지막으로 확인했던 상태가 그대로 남는다.
     *
     * @param checkedAt 배치가 이 가게를 처리한 시각
     */
    public void applyNoBizNo(LocalDateTime checkedAt) {
        this.checkResult = NtsLookupResult.NO_BIZ_NO;
        this.lastAttemptAt = checkedAt;
    }
}
