package com.ktc4.backend.domain.checkin.entity;

import com.ktc4.backend.domain.store.entity.Store;
import com.ktc4.backend.global.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 점주가 아동의 QR 을 찍어 방문을 확인한 기록. 방문할 때마다 한 줄씩 쌓인다.
 *
 * <p>체크인 시각은 {@code createdAt} 이다. 아동은 이름 없이 번호({@code childId})만 남긴다 —
 * 월간 리포트의 "방문 아동 수"를 세는 데 필요한 만큼만이다.
 *
 * <p>이 기록은 나중에 가게의 다음 검토일 계산에도 쓰인다(최근 체크인 시각). 그래서 체크인할 때
 * {@link Store} 를 고치지 않고 기록만 남긴다.
 */
@Entity
@Table(name = "check_in")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CheckIn extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "check_in_id")
    private Long checkInId;

    @Column(name = "child_id", nullable = false)
    private Long childId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "store_id", nullable = false)
    private Store store;

    private CheckIn(Long childId, Store store) {
        this.childId = childId;
        this.store = store;
    }

    /**
     * 체크인 기록을 만든다.
     *
     * @param childId QR 로 확인한 아동 번호
     * @param store   체크인한 가게
     * @return 저장 전 기록
     */
    public static CheckIn of(Long childId, Store store) {
        return new CheckIn(childId, store);
    }
}
