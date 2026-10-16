package com.ktc4.backend.domain.member.repository;

import com.ktc4.backend.domain.member.entity.StoreOwner;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface StoreOwnerRepository extends JpaRepository<StoreOwner, Long> {

    /**
     * 그 회원이 그 가게에 연결돼 있는지 — 체크인할 때 "내 가게"인지 확인하는 데 쓴다. 끊긴 연결은 세지 않는다.
     *
     * @param storeId  가게 ID
     * @param memberId 회원 ID
     * @return 끊기지 않은 연결이 있으면 true
     */
    @Query("""
            select count(so) > 0
            from StoreOwner so
            where so.store.storeId = :storeId and so.member.memberId = :memberId
              and so.unlinkedAt is null
            """)
    boolean existsByStoreIdAndMemberId(@Param("storeId") Long storeId, @Param("memberId") Long memberId);

    /**
     * 그 가게와 그 회원의 연결을 읽는다. 끊긴 연결도 돌려준다 — 다시 연결할 때 그 행을 되살려야 하기 때문이다.
     *
     * @param storeId  가게 ID
     * @param memberId 회원 ID
     * @return 연결. 한 번도 연결된 적이 없으면 빈 값
     */
    @Query("""
            select so
            from StoreOwner so
            where so.store.storeId = :storeId and so.member.memberId = :memberId
            """)
    Optional<StoreOwner> findByStoreIdAndMemberId(@Param("storeId") Long storeId, @Param("memberId") Long memberId);

    /**
     * 가게별로 연결된 점주 수를 센다. 연결이 하나도 없는 가게는 결과에 없다. 끊긴 연결은 세지 않는다.
     *
     * @param storeIds 셀 가게 ID. 비어 있으면 안 된다
     * @return 연결이 있는 가게의 ID 와 점주 수
     */
    @Query("""
            select so.store.storeId as storeId, count(so) as ownerCount
            from StoreOwner so
            where so.store.storeId in :storeIds
              and so.unlinkedAt is null
            group by so.store.storeId
            """)
    List<OwnerCount> countOwnersByStoreIds(@Param("storeIds") Collection<Long> storeIds);

    /**
     * 회원에게 연결된 가게를 처음 연결한 순서대로 읽는다. 끊긴 연결은 뺀다.
     *
     * <p>점주 앱은 첫 번째 가게로 체크인하므로 순서가 바뀌지 않아야 한다. 그래서 {@code linkedAt} 이 아니라 연결 행의 ID 로
     * 정렬한다 — 끊었다 다시 이으면 같은 행을 되살리면서 {@code linkedAt} 만 새 시각이 되는데({@link StoreOwner#relink}),
     * {@code linkedAt} 으로 정렬하면 그때 순서가 뒤집혀 앱이 다른 가게로 체크인하게 된다. 가게 이름까지 한 번에 읽어
     * 가게마다 따로 조회하지 않는다.
     *
     * @param memberId 회원 ID
     * @return 연결된 가게. 없으면 빈 목록
     */
    @Query("""
            select s.storeId as storeId, s.name as name
            from StoreOwner so
            join so.store s
            where so.member.memberId = :memberId
              and so.unlinkedAt is null
            order by so.storeOwnerId asc
            """)
    List<LinkedStore> findLinkedStoresByMemberId(@Param("memberId") Long memberId);

    /**
     * 회원에게 연결된 가게를 관리자 화면에 보여줄 만큼 읽는다. 끊긴 연결은 뺀다.
     *
     * <p>{@link #findLinkedStoresByMemberId}(점주 앱용)와 따로 두는 이유는 두 가지다. 관리자는 지점을
     * 구분해야 해서 주소와 언제 연결했는지가 필요하고, 점주 앱 쪽 질의의 칸을 늘리면 앱이 쓰지도 않는 값을
     * 매번 읽게 된다. 정렬 기준(연결 행 ID)은 같게 둔다 — 두 화면이 다른 순서를 보이면 이야기가 어긋난다.
     *
     * @param memberId 회원 ID
     * @return 연결된 가게. 없으면 빈 목록
     */
    @Query("""
            select s.storeId as storeId, s.name as name, s.addressRoad as addressRoad,
                   so.linkedAt as linkedAt
            from StoreOwner so
            join so.store s
            where so.member.memberId = :memberId
              and so.unlinkedAt is null
            order by so.storeOwnerId asc
            """)
    List<LinkedStoreDetail> findLinkedStoreDetailsByMemberId(@Param("memberId") Long memberId);

    /**
     * 그 가게에 아직 끊기지 않은 점주 연결이 있는지 — 가게를 지워도 되는지 판단하는 데 쓴다.
     *
     * <p>연결된 채로 가게를 지우면 점주가 쓰던 가게가 말없이 사라진다. 그래서 하나라도 있으면
     * 삭제를 거절하고({@code STORE_HAS_OWNER}) 연결을 먼저 끊게 한다
     * ({@code DELETE /api/admin/owners/{memberId}/stores/{storeId}}).
     *
     * @param storeId 가게 ID
     * @return 끊기지 않은 연결이 있으면 true
     */
    @Query("""
            select count(so) > 0
            from StoreOwner so
            where so.store.storeId = :storeId and so.unlinkedAt is null
            """)
    boolean existsLinkedByStoreId(@Param("storeId") Long storeId);

    /**
     * 그 가게의 점주 연결을 한 번의 {@code delete} 문으로 지운다 — 가게를 지울 때 부른다.
     *
     * <p>끊긴 연결({@code unlinkedAt} 이 있는 행)까지 함께 지운다. 가게가 사라지면 그 연결 기록도
     * 가리킬 곳이 없다.
     *
     * @param storeId 가게 ID
     * @return 지운 줄 수
     */
    @Modifying
    @Query("delete from StoreOwner so where so.store.storeId = :storeId")
    int deleteAllByStoreId(@Param("storeId") Long storeId);

    /** 관리자 화면이 보는 연결된 가게 한 곳. */
    interface LinkedStoreDetail {
        Long getStoreId();

        String getName();

        String getAddressRoad();

        LocalDateTime getLinkedAt();
    }

    /** 가게 한 곳에 연결된 점주 수. */
    interface OwnerCount {

        Long getStoreId();

        long getOwnerCount();
    }

    /** 회원에게 연결된 가게 한 곳. */
    interface LinkedStore {

        Long getStoreId();

        String getName();
    }
}
