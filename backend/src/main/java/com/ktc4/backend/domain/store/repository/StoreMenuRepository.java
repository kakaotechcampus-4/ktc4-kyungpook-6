package com.ktc4.backend.domain.store.repository;

import com.ktc4.backend.domain.store.entity.StoreMenu;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StoreMenuRepository extends JpaRepository<StoreMenu, Long> {

    boolean existsByStoreStoreId(Long storeId);
}
