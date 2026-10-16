package com.ktc4.backend.domain.store.repository;

import com.ktc4.backend.domain.store.entity.StoreChangeHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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
}
