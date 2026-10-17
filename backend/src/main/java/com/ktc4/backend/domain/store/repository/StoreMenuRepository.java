package com.ktc4.backend.domain.store.repository;

import com.ktc4.backend.domain.store.entity.StoreMenu;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface StoreMenuRepository extends JpaRepository<StoreMenu, Long> {

    boolean existsByStoreStoreIdAndDeletedAtIsNull(Long storeId);

    List<StoreMenu> findByStoreStoreIdAndDeletedAtIsNullOrderByMenuIdAsc(Long storeId);

    /**
     * 그 가게의 메뉴를 한 번의 {@code delete} 문으로 지운다 — 가게를 지울 때 부른다.
     *
     * <p>지운 표시({@code deletedAt})가 있는 줄까지 함께 지운다. 가게가 사라지면 그 메뉴도 남을 이유가 없다.
     *
     * @param storeId 가게 ID
     * @return 지운 줄 수
     */
    @Modifying
    @Query("delete from StoreMenu m where m.store.storeId = :storeId")
    int deleteAllByStoreId(@Param("storeId") Long storeId);
}
