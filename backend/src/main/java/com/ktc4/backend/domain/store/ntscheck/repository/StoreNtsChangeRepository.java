package com.ktc4.backend.domain.store.ntscheck.repository;

import com.ktc4.backend.domain.store.ntscheck.entity.StoreNtsChange;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 국세청 상태 변경 이력 리포지토리. 배치가 추가만 하고 수정·삭제하지 않는다.
 */
public interface StoreNtsChangeRepository extends JpaRepository<StoreNtsChange, Long> {
}
