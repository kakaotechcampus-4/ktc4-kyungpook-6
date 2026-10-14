package com.ktc4.backend.domain.store.repository;

import com.ktc4.backend.domain.store.entity.StoreMenu;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface StoreMenuRepository extends JpaRepository<StoreMenu, Long> {

    boolean existsByStoreStoreIdAndDeletedAtIsNull(Long storeId);

    List<StoreMenu> findByStoreStoreIdAndDeletedAtIsNullOrderByMenuIdAsc(Long storeId);
}
