package com.ktc4.backend.domain.store.repository;

import com.ktc4.backend.domain.store.entity.StoreChangeHistory;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StoreChangeHistoryRepository extends JpaRepository<StoreChangeHistory, Long> {
}
