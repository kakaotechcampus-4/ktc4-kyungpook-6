package com.ktc4.backend.domain.store.repository;

import com.ktc4.backend.domain.store.dto.StoreLastChange;
import com.ktc4.backend.domain.store.entity.StoreChangeHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface StoreChangeHistoryRepository extends JpaRepository<StoreChangeHistory, Long> {

    /**
     * 그 가게의 변경 기록을 한 번의 {@code delete} 문으로 지운다 — 가게를 지울 때 부른다.
     *
     * <p>{@code store_id} 가 NOT NULL 외래키라, 이 줄을 남긴 채 가게를 지우면 제약에 걸려 터진다.
     * 기록은 "그 가게의 어떤 값이 어떻게 바뀌었나" 라서, 가게가 사라지면 가리킬 곳이 없다
     * ({@link StoreChangeHistory} Javadoc · PR #113 리뷰).
     *
     * @param storeId 가게 ID
     * @return 지운 줄 수
     */
    @Modifying
    @Query("delete from StoreChangeHistory h where h.store.storeId = :storeId")
    int deleteAllByStoreId(@Param("storeId") Long storeId);

    /**
     * 그 가게들이 마지막으로 바뀐 시각. 한 번도 바뀌지 않은 가게는 결과에 없다.
     *
     * <p>화면이 "마지막 확인" 과 "최근 변경" 을 따로 보여주기 위한 값이다. 담당자가 수정안을 반영해도
     * 확인일은 오르지 않는데(그건 사람이 직접 본 날이다), 날짜가 하나뿐이면 "반영했는데 왜 그대로지"
     * 로 보인다(11주차 멘토 리뷰).
     *
     * <p>목록 한 쪽(20건)을 쿼리 한 번으로 가져온다 — 가게마다 따로 물으면 N+1 이다.
     *
     * @param storeIds 가게 id 목록
     * @return 바뀐 적이 있는 가게만, 가게별 마지막 변경 시각
     */
    @Query("""
            select new com.ktc4.backend.domain.store.dto.StoreLastChange(h.store.storeId, max(h.changedAt))
            from StoreChangeHistory h
            where h.store.storeId in :storeIds
            group by h.store.storeId
            """)
    List<StoreLastChange> findLastChangedAtByStoreIds(@Param("storeIds") Collection<Long> storeIds);
}
