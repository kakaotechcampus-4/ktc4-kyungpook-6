package com.ktc4.backend.domain.store.repository;

import com.ktc4.backend.domain.store.entity.StoreBusinessHour;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StoreBusinessHourRepository extends JpaRepository<StoreBusinessHour, Long> {

    boolean existsByStoreStoreId(Long storeId);
}
