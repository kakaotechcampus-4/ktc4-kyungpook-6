package com.ktc4.backend.domain.member.repository;

import com.ktc4.backend.domain.member.entity.StoreOwner;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface StoreOwnerRepository extends JpaRepository<StoreOwner, Long> {

    /**
     * 그 회원이 그 가게에 연결돼 있는지 — 체크인할 때 "내 가게"인지 확인하는 데 쓴다.
     *
     * @param storeId  가게 ID
     * @param memberId 회원 ID
     * @return 연결이 있으면 true
     */
    @Query("""
            select count(so) > 0
            from StoreOwner so
            where so.store.storeId = :storeId and so.member.memberId = :memberId
            """)
    boolean existsByStoreIdAndMemberId(@Param("storeId") Long storeId, @Param("memberId") Long memberId);

    /**
     * 가게별로 연결된 점주 수를 센다. 연결이 하나도 없는 가게는 결과에 없다.
     *
     * @param storeIds 셀 가게 ID. 비어 있으면 안 된다
     * @return 연결이 있는 가게의 ID 와 점주 수
     */
    @Query("""
            select so.store.storeId as storeId, count(so) as ownerCount
            from StoreOwner so
            where so.store.storeId in :storeIds
            group by so.store.storeId
            """)
    List<OwnerCount> countOwnersByStoreIds(@Param("storeIds") Collection<Long> storeIds);

    /** 가게 한 곳에 연결된 점주 수. */
    interface OwnerCount {

        Long getStoreId();

        long getOwnerCount();
    }
}
