package com.ktc4.backend.domain.store.ntscheck.repository;

import com.ktc4.backend.domain.store.ntscheck.entity.StoreNtsChange;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 국세청 상태 변경 이력 리포지토리. 배치가 추가만 하고 수정·삭제하지 않는다.
 */
public interface StoreNtsChangeRepository extends JpaRepository<StoreNtsChange, Long> {

    /**
     * 그 가게의 상태 변경 이력을 한 번의 {@code delete} 문으로 지운다 — 가게를 지울 때 부른다.
     *
     * @param storeId 가게 ID
     * @return 지운 줄 수
     */
    @Modifying
    @Query("delete from StoreNtsChange c where c.store.storeId = :storeId")
    int deleteAllByStoreId(@Param("storeId") Long storeId);
}
