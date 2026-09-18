package com.ktc4.backend.domain.store.ntscheck.repository;

import com.ktc4.backend.domain.business.enums.BusinessState;
import com.ktc4.backend.domain.store.entity.Store;
import com.ktc4.backend.domain.store.enums.StoreStatus;
import com.ktc4.backend.domain.store.ntscheck.entity.StoreNtsChange;
import com.ktc4.backend.support.PostgresContainerTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * StoreNtsChange 저장/조회 통합 테스트.
 *
 * <p>이력 테이블이라 수정 없이 INSERT 만 한다. {@code fromState} 는 그 가게의 국세청 상태를 처음
 * 확인한 경우 비어 있을 수 있어 nullable 이고, {@code toState} 는 항상 있어야 한다.
 */
class StoreNtsChangeRepositoryTest extends PostgresContainerTest {

    @Autowired
    private StoreNtsChangeRepository storeNtsChangeRepository;

    @Autowired
    private TestEntityManager entityManager;

    private Store persistedStore() {
        return entityManager.persistAndFlush(Store.builder()
                .name("예시분식")
                .nameNormalized("예시분식")
                .addressRoad("가상특별시 예시구 샘플로 123")
                .addressNormalized("가상특별시예시구샘플로123")
                .status(StoreStatus.OPEN)
                .bizNo("1234567890")
                .build());
    }

    @Test
    void 저장한_상태변경_이력을_그대로_조회할_수_있다() {
        Store store = persistedStore();
        LocalDateTime detectedAt = LocalDateTime.now();
        StoreNtsChange change = StoreNtsChange.builder()
                .store(store)
                .fromState(BusinessState.ACTIVE)
                .toState(BusinessState.CLOSED)
                .detectedAt(detectedAt)
                .build();

        Long savedId = entityManager.persistAndFlush(change).getStoreNtsChangeId();
        entityManager.clear();

        StoreNtsChange found = storeNtsChangeRepository.findById(savedId).orElseThrow();

        assertThat(found.getStore().getStoreId()).isEqualTo(store.getStoreId());
        assertThat(found.getFromState()).isEqualTo(BusinessState.ACTIVE);
        assertThat(found.getToState()).isEqualTo(BusinessState.CLOSED);
        assertThat(found.getDetectedAt()).isEqualToIgnoringNanos(detectedAt);
        assertThat(found.getCreatedAt()).isNotNull();
    }

    @Test
    void 처음_확인한_상태는_이전_상태가_비어_있는_이력으로_남는다() {
        StoreNtsChange change = StoreNtsChange.builder()
                .store(persistedStore())
                .toState(BusinessState.ACTIVE)
                .detectedAt(LocalDateTime.now())
                .build();

        Long savedId = entityManager.persistAndFlush(change).getStoreNtsChangeId();
        entityManager.clear();

        assertThat(storeNtsChangeRepository.findById(savedId).orElseThrow().getFromState()).isNull();
    }

    @ParameterizedTest
    @EnumSource(BusinessState.class)
    void toState는_모든_enum_값이_왕복된다(BusinessState toState) {
        StoreNtsChange change = StoreNtsChange.builder()
                .store(persistedStore())
                .toState(toState)
                .detectedAt(LocalDateTime.now())
                .build();

        Long savedId = entityManager.persistAndFlush(change).getStoreNtsChangeId();
        entityManager.clear();

        assertThat(storeNtsChangeRepository.findById(savedId).orElseThrow().getToState()).isEqualTo(toState);
    }

    @Test
    void toState가_없으면_저장에_실패한다() {
        StoreNtsChange change = StoreNtsChange.builder()
                .store(persistedStore())
                .fromState(BusinessState.ACTIVE)
                .detectedAt(LocalDateTime.now())
                .build();

        assertThatThrownBy(() -> storeNtsChangeRepository.saveAndFlush(change))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void detectedAt이_없으면_저장에_실패한다() {
        StoreNtsChange change = StoreNtsChange.builder()
                .store(persistedStore())
                .toState(BusinessState.CLOSED)
                .build();

        assertThatThrownBy(() -> storeNtsChangeRepository.saveAndFlush(change))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void store가_없으면_저장에_실패한다() {
        StoreNtsChange change = StoreNtsChange.builder()
                .toState(BusinessState.CLOSED)
                .detectedAt(LocalDateTime.now())
                .build();

        assertThatThrownBy(() -> storeNtsChangeRepository.saveAndFlush(change))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
