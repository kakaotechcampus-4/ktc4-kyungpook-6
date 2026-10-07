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
}
