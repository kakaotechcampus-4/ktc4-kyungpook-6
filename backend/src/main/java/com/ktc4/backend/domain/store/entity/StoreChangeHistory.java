package com.ktc4.backend.domain.store.entity;

import com.ktc4.backend.domain.store.dto.StoreChange;
import com.ktc4.backend.domain.store.enums.StoreChangeField;
import com.ktc4.backend.domain.store.enums.StoreChangeSource;
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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

/**
 * 가게 정보가 바뀐 기록 — 무엇이, 무엇에서 무엇으로, 누가, 어느 경로로. 바뀐 항목마다 한 줄이고, 한 번 쌓으면 고치지 않는다.
 *
 * <p>가게에는 지금 값만 남아, 관리자가 확인한 값을 점주가 바꾸면 원래 값이 사라졌다. 그 값을 되짚을 수 있게 남긴다.
 * 조회 API 는 아직 없다 — DB 에서 본다.
 *
 * <p>값({@code before_value}·{@code after_value})은 항목별로 이 모양의 JSON 이다. 없는 값은 SQL NULL 이다.
 * <ul>
 *   <li>{@code NAME}·{@code ADDRESS_ROAD}·{@code PHONE}·{@code STATUS} — 문자열 ({@code "053-123-4567"}).
 *       전화는 가게 대표번호다. 점주 개인 휴대폰은 기록하지 않는다</li>
 *   <li>{@code BUSINESS_HOURS} — {@code [{"day":"MON","open":"11:00","close":"21:00"}]}, 요일 순·같은 요일은 여는 시간 순.
 *       {@code close} 가 {@code open} 보다 이르면 다음 날 닫는다, {@code "24:00"} 은 그날 끝 자정</li>
 *   <li>{@code MENUS} — {@code [{"menuId":3,"name":"김치찌개","onSale":true}]}, 지우지 않은 메뉴만 {@code menuId} 순</li>
 * </ul>
 *
 * <p>같은 요청으로 바뀐 줄들은 {@code changedAt}·{@code source}·{@code changedBy} 가 같다. 같은 가게에 동시에 온
 * 변경의 순서는 {@code changedAt} 이 아니라 ID 로 본다 — 시각은 가게를 잠그기 전에 정해지지만, 줄은 잠금 안에서
 * 들어가 ID 가 실제 순서대로 매겨진다. 그래서 인덱스도 (가게, ID) 다. 같은 요청의 줄은 항목 선언 순서({@link StoreChangeField})로
 * 들어간다.
 *
 * <p>회원·조사 결과는 ID 만 둔다(외래 키 없음) — 가게 도메인이 회원·조사 엔티티를 매핑하지 않게. 보존 기한은 아직
 * 없다. 가게를 지우는 기능이 생기면 이 기록도 같이 지워야 한다(가게 외래 키).
 */
@Entity
@Table(
        name = "store_change_history",
        indexes = @Index(name = "idx_store_change_history_store_id", columnList = "store_id, store_change_history_id")
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StoreChangeHistory extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "store_change_history_id")
    private Long storeChangeHistoryId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "store_id", nullable = false)
    private Store store;

    @Enumerated(EnumType.STRING)
    @Column(name = "field", nullable = false, length = 30)
    private StoreChangeField field;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 30)
    private StoreChangeSource source;

    /** 바꾼 회원 ID (관리자 또는 점주) */
    @Column(name = "changed_by", nullable = false)
    private Long changedBy;

    /** 조사 수정안 반영일 때 그 조사 결과 ID */
    @Column(name = "task_id")
    private Long taskId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "before_value")
    private String beforeValue;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "after_value")
    private String afterValue;

    /** 요청을 처리한 시각. 행이 생긴 시각({@code createdAt})과는 별개다 */
    @Column(name = "changed_at", nullable = false)
    private LocalDateTime changedAt;

    private StoreChangeHistory(Store store, StoreChangeField field, StoreChange change,
                               String beforeValue, String afterValue) {
        this.store = store;
        this.field = field;
        this.source = change.source();
        this.changedBy = change.changedBy();
        this.taskId = change.taskId();
        this.beforeValue = beforeValue;
        this.afterValue = afterValue;
        this.changedAt = change.changedAt();
    }

    /**
     * 항목 하나가 바뀐 기록을 만든다.
     *
     * @param store       가게
     * @param field       바뀐 항목
     * @param change      어느 경로로·누가·언제
     * @param beforeValue 바꾸기 전 값 (JSON). 없었으면 {@code null}
     * @param afterValue  바꾼 뒤 값 (JSON). 없어졌으면 {@code null}
     * @return 저장 전 기록
     */
    public static StoreChangeHistory of(Store store, StoreChangeField field, StoreChange change,
                                        String beforeValue, String afterValue) {
        return new StoreChangeHistory(store, field, change, beforeValue, afterValue);
    }
}
