package com.ktc4.backend.domain.store.ntscheck.repository;

import com.ktc4.backend.domain.store.ntscheck.entity.StoreNtsCheck;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 가게별 국세청 최신 확인 기록 리포지토리. 식별자는 가게 ID 다(가게당 한 행).
 *
 * <p>배치는 {@code findAllById} 로 청크에 속한 가게의 기존 기록을 한 번에 읽고,
 * {@code saveAll} 로 한 번에 저장한다. 그 외의 조회 메서드는 아직 쓰는 곳이 없어 만들지 않는다.
 */
public interface StoreNtsCheckRepository extends JpaRepository<StoreNtsCheck, Long> {
}
