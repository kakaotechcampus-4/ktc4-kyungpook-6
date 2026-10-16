package com.ktc4.backend.domain.store.ntscheck.repository;

import com.ktc4.backend.domain.store.ntscheck.entity.StoreNtsCheck;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 가게별 국세청 최신 확인 기록 리포지토리. 식별자는 가게 ID 다(가게당 한 행).
 *
 * <p>배치는 {@code findAllById} 로 청크에 속한 가게의 기존 기록을 한 번에 읽고,
 * {@code saveAll} 로 한 번에 저장한다. 그 외의 조회 메서드는 아직 쓰는 곳이 없어 만들지 않는다.
 */
public interface StoreNtsCheckRepository extends JpaRepository<StoreNtsCheck, Long> {

    /**
     * 그 가게의 국세청 확인 기록을 지운다 — 가게를 지울 때 부른다.
     *
     * <p>식별자가 가게 ID 라 {@code deleteById} 로도 되지만, 그러면 행이 있는지 먼저 읽어야 하고
     * 없을 때 예외가 난다. 한 번의 {@code delete} 문으로 두면 있든 없든 같은 호출로 끝난다.
     *
     * @param storeId 가게 ID
     * @return 지운 줄 수(0 또는 1)
     */
    @Modifying
    @Query("delete from StoreNtsCheck c where c.store.storeId = :storeId")
    int deleteAllByStoreId(@Param("storeId") Long storeId);
}
