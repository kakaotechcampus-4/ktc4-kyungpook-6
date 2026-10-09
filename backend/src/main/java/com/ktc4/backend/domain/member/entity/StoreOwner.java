package com.ktc4.backend.domain.member.entity;

import com.ktc4.backend.domain.member.enums.MemberRole;
import com.ktc4.backend.domain.store.entity.Store;
import com.ktc4.backend.global.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 점주 계정과 가게의 연결. 이 연결이 있어야 그 점주가 그 가게에 아동 방문을 기록할 수 있다.
 *
 * <p>가게나 회원에 칼럼으로 두지 않고 테이블로 둔다. 한 가게에 계정이 여럿 붙을 수 있어야 하기 때문이다
 * (공동 대표, 가게를 넘겨받은 새 점주, 나중의 직원 계정). 지금은 관리자가 가입을 승인할 때 한 건만 만든다.
 *
 * <p>아동의 방문 기록을 다루므로 "왜 이 사람이 이 가게 점주로 인정됐는가"를 남긴다 —
 * {@code linkedAt} 은 언제, {@code linkedBy} 는 어느 관리자가 인정했는지다.
 *
 * <p>연결을 끊어도 행을 지우지 않는다. {@code unlinkedAt}·{@code unlinkedBy} 를 채워 누가 언제 끊었는지 남기고,
 * 끊긴 연결은 체크인 권한을 주지 않는다({@link #isActive()}). 같은 가게·점주의 행은 하나뿐이라, 다시 연결하면
 * 그 행을 되살린다 — 그때 앞선 연결 기간의 기록은 덮인다.
 */
@Entity
@Table(
        name = "store_owner",
        uniqueConstraints = @UniqueConstraint(
                name = StoreOwner.STORE_MEMBER_UNIQUE_CONSTRAINT, columnNames = {"store_id", "member_id"}),
        indexes = @Index(name = "idx_store_owner_member_id", columnList = "member_id")
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StoreOwner extends BaseTimeEntity {

    /** 같은 점주를 같은 가게에 두 번 연결하지 못하게 막는 DB 제약 이름. */
    public static final String STORE_MEMBER_UNIQUE_CONSTRAINT = "uk_store_owner_store_member";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "store_owner_id")
    private Long storeOwnerId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "store_id", nullable = false)
    private Store store;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "member_id", nullable = false)
    private Member member;

    /** 연결된 시각 */
    @Column(name = "linked_at", nullable = false)
    private LocalDateTime linkedAt;

    /** 연결을 인정한 관리자 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "linked_by", nullable = false)
    private Member linkedBy;

    /** 연결을 끊은 시각. 연결돼 있는 동안에는 비어 있다. */
    @Column(name = "unlinked_at")
    private LocalDateTime unlinkedAt;

    /** 연결을 끊은 관리자. 연결돼 있는 동안에는 비어 있다. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "unlinked_by")
    private Member unlinkedBy;

    private StoreOwner(Store store, Member member, LocalDateTime linkedAt, Member linkedBy) {
        this.store = store;
        this.member = member;
        this.linkedAt = linkedAt;
        this.linkedBy = linkedBy;
    }

    /**
     * 점주를 가게에 연결한다.
     *
     * @param store    연결할 가게
     * @param owner    연결할 점주
     * @param linkedBy 연결을 인정한 관리자. 역할은 호출자가 확인한다 — 여기서 읽으면 참조만 넘긴 경우에도 조회가 나간다
     * @param linkedAt 연결 시각
     * @return 저장 전 연결
     * @throws IllegalArgumentException 점주가 아닌 회원을 연결하려 하면
     */
    public static StoreOwner link(Store store, Member owner, Member linkedBy, LocalDateTime linkedAt) {
        if (owner.getRole() != MemberRole.OWNER) {
            throw new IllegalArgumentException("점주만 가게에 연결할 수 있습니다 - memberId=" + owner.getMemberId());
        }
        return new StoreOwner(store, owner, linkedAt, linkedBy);
    }

    /** 지금 연결돼 있는지. 끊긴 연결은 체크인 권한을 주지 않는다. */
    public boolean isActive() {
        return unlinkedAt == null;
    }

    /**
     * 연결을 끊는다. 행을 지우지 않고 누가 언제 끊었는지 남긴다 — 연결할 때 남기는 것과 같은 이유다.
     *
     * @param unlinkedBy 끊은 관리자
     * @param unlinkedAt 끊은 시각
     * @throws IllegalStateException 이미 끊긴 연결이면
     */
    public void unlink(Member unlinkedBy, LocalDateTime unlinkedAt) {
        if (!isActive()) {
            throw new IllegalStateException("이미 끊긴 연결입니다 - storeOwnerId=" + storeOwnerId);
        }
        this.unlinkedAt = unlinkedAt;
        this.unlinkedBy = unlinkedBy;
    }

    /**
     * 끊긴 연결을 다시 잇는다. 같은 가게·점주의 행은 하나뿐이라 새로 만들지 않고 이 행을 되살린다.
     * 인정한 관리자와 시각은 이번 것으로 바뀐다.
     *
     * @param linkedBy 다시 연결을 인정한 관리자
     * @param linkedAt 다시 연결한 시각
     * @throws IllegalStateException 끊기지 않은 연결이면
     */
    public void relink(Member linkedBy, LocalDateTime linkedAt) {
        if (isActive()) {
            throw new IllegalStateException("이미 연결돼 있습니다 - storeOwnerId=" + storeOwnerId);
        }
        this.linkedAt = linkedAt;
        this.linkedBy = linkedBy;
        this.unlinkedAt = null;
        this.unlinkedBy = null;
    }
}
