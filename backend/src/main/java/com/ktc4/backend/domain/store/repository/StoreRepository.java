package com.ktc4.backend.domain.store.repository;

import com.ktc4.backend.domain.store.entity.Store;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Store 조회/저장 리포지토리.
 *
 * <p>메서드를 하나도 선언하지 않아도 {@link JpaRepository} 를 상속하는 것만으로
 * 저장·단건조회·페이지 조회·개수 세기 구현체가 런타임에 생성된다.
 */
public interface StoreRepository extends JpaRepository<Store, Long> {
}
