package com.ktc4.backend.domain.store.entity;

import com.ktc4.backend.domain.store.enums.BusinessDay;
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
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Check;

/**
 * 가게의 영업시간 한 줄. 같은 요일이 여러 줄일 수 있고(브레이크타임), 줄이 없는 요일은 휴무다.
 *
 * <p>시간은 자정부터 지난 분으로 저장한다 — 여는 시간 0 ~ 1439, 닫는 시간 1 ~ 1440. 그날 끝 자정({@code "24:00"})을
 * {@code LocalTime} 으로는 담을 수 없어서다. 닫는 시간이 여는 시간보다 이르면 다음 날 닫는 것이다(예: 21:00 ~ 02:00).
 *
 * <p>같은 값 금지 조건(unique)은 두지 않는다. 영업시간은 목록을 통째로 바꾸는데, 그때 Hibernate 가 지우기보다 넣기를
 * 먼저 내보내면 같은 줄이 잠깐 둘이 되어 충돌한다. 줄끼리 겹치지 않는지는 요청 검증이 막는다.
 *
 * <p>요일은 이름으로 저장해 DB 에서 정렬하면 알파벳순(FRI, MON, …)이 된다 — 요일 순서는 자바에서 맞춘다.
 */
@Entity
@Table(
        name = "store_business_hour",
        indexes = @Index(name = "idx_store_business_hour_store_id", columnList = "store_id")
)
@Check(name = "ck_store_business_hour_minutes",
        constraints = "open_minute between 0 and 1439 and close_minute between 1 and 1440 and open_minute <> close_minute")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StoreBusinessHour extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "business_hour_id")
    private Long businessHourId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "store_id", nullable = false)
    private Store store;

    @Enumerated(EnumType.STRING)
    @Column(name = "day", nullable = false, length = 3)
    private BusinessDay day;

    /** 여는 시간 — 자정부터 지난 분 (0 ~ 1439) */
    @Column(name = "open_minute", nullable = false)
    private int openMinute;

    /** 닫는 시간 — 자정부터 지난 분 (1 ~ 1440). 여는 시간보다 작으면 다음 날 닫는다 */
    @Column(name = "close_minute", nullable = false)
    private int closeMinute;

    private StoreBusinessHour(Store store, BusinessDay day, int openMinute, int closeMinute) {
        this.store = store;
        this.day = day;
        this.openMinute = openMinute;
        this.closeMinute = closeMinute;
    }

    /**
     * 영업시간 한 줄을 만든다.
     *
     * @param store       가게
     * @param day         요일
     * @param openMinute  여는 시간 (0 ~ 1439)
     * @param closeMinute 닫는 시간 (1 ~ 1440)
     * @return 저장 전 영업시간 한 줄
     */
    public static StoreBusinessHour of(Store store, BusinessDay day, int openMinute, int closeMinute) {
        return new StoreBusinessHour(store, day, openMinute, closeMinute);
    }
}
