package com.ktc4.backend.domain.store.ntscheck.repository;

import com.ktc4.backend.domain.business.enums.BusinessState;
import com.ktc4.backend.domain.store.entity.Store;
import com.ktc4.backend.domain.store.enums.NtsLookupResult;
import com.ktc4.backend.domain.store.enums.StoreStatus;
import com.ktc4.backend.domain.store.ntscheck.entity.StoreNtsCheck;
import com.ktc4.backend.support.PostgresContainerTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * StoreNtsCheck 저장/조회 통합 테스트.
 *
 * <p>{@code store_id}를 PK 이자 FK 로 함께 쓰는 공유 PK 매핑({@code @MapsId})이라, 실제 Postgres 에
 * 붙여서 저장·재조회가 되는지 확인해야 매핑이 맞는지 알 수 있다. {@code persistAndFlush} 후
 * {@code clear} 로 1차 캐시를 비우고 다시 읽는 이유는 {@code repository_test_checklist.md} 1번 참고.
 */
class StoreNtsCheckRepositoryTest extends PostgresContainerTest {

    @Autowired
    private StoreNtsCheckRepository storeNtsCheckRepository;

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
    void 저장한_국세청_확인기록을_가게ID로_그대로_조회할_수_있다() {
        Store store = persistedStore();
        LocalDateTime attemptedAt = LocalDateTime.now();
        StoreNtsCheck check = StoreNtsCheck.builder()
                .store(store)
                .bizNo("1234567890")
                .checkResult(NtsLookupResult.CONFIRMED)
                .ntsState(BusinessState.CLOSED)
                .ntsClosedAt(LocalDate.of(2026, 3, 1))
                .lastAttemptAt(attemptedAt)
                .lastSuccessAt(attemptedAt)
                .build();

        entityManager.persistAndFlush(check);
        entityManager.clear();

        StoreNtsCheck found = storeNtsCheckRepository.findById(store.getStoreId()).orElseThrow();

        assertThat(found.getStoreId()).isEqualTo(store.getStoreId());
        assertThat(found.getStore().getStoreId()).isEqualTo(store.getStoreId());
        assertThat(found.getBizNo()).isEqualTo("1234567890");
        assertThat(found.getCheckResult()).isEqualTo(NtsLookupResult.CONFIRMED);
        assertThat(found.getNtsState()).isEqualTo(BusinessState.CLOSED);
        assertThat(found.getNtsClosedAt()).isEqualTo(LocalDate.of(2026, 3, 1));
        assertThat(found.getLastAttemptAt()).isEqualToIgnoringNanos(attemptedAt);
        assertThat(found.getLastSuccessAt()).isEqualToIgnoringNanos(attemptedAt);
        assertThat(found.getCreatedAt()).isNotNull();
    }

    @Test
    void 아직_한_번도_성공하지_못한_기록은_국세청_상태와_성공시각이_비어_있다() {
        Store store = persistedStore();
        StoreNtsCheck check = StoreNtsCheck.builder()
                .store(store)
                .bizNo("1234567890")
                .checkResult(NtsLookupResult.UNCONFIRMED)
                .lastAttemptAt(LocalDateTime.now())
                .build();

        entityManager.persistAndFlush(check);
        entityManager.clear();

        StoreNtsCheck found = storeNtsCheckRepository.findById(store.getStoreId()).orElseThrow();

        assertThat(found.getNtsState()).isNull();
        assertThat(found.getNtsClosedAt()).isNull();
        assertThat(found.getLastSuccessAt()).isNull();
    }

    @ParameterizedTest
    @EnumSource(NtsLookupResult.class)
    void checkResult는_모든_enum_값이_왕복된다(NtsLookupResult checkResult) {
        Store store = persistedStore();
        StoreNtsCheck check = StoreNtsCheck.builder()
                .store(store)
                .checkResult(checkResult)
                .lastAttemptAt(LocalDateTime.now())
                .build();

        entityManager.persistAndFlush(check);
        entityManager.clear();

        assertThat(storeNtsCheckRepository.findById(store.getStoreId()).orElseThrow().getCheckResult())
                .isEqualTo(checkResult);
    }

    @ParameterizedTest
    @EnumSource(BusinessState.class)
    void ntsState는_모든_enum_값이_왕복된다(BusinessState ntsState) {
        Store store = persistedStore();
        StoreNtsCheck check = StoreNtsCheck.builder()
                .store(store)
                .checkResult(NtsLookupResult.CONFIRMED)
                .ntsState(ntsState)
                .lastAttemptAt(LocalDateTime.now())
                .build();

        entityManager.persistAndFlush(check);
        entityManager.clear();

        assertThat(storeNtsCheckRepository.findById(store.getStoreId()).orElseThrow().getNtsState())
                .isEqualTo(ntsState);
    }

    @Test
    void checkResult가_없으면_저장에_실패한다() {
        StoreNtsCheck check = StoreNtsCheck.builder()
                .store(persistedStore())
                .lastAttemptAt(LocalDateTime.now())
                .build();

        assertThatThrownBy(() -> storeNtsCheckRepository.saveAndFlush(check))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void lastAttemptAt이_없으면_저장에_실패한다() {
        StoreNtsCheck check = StoreNtsCheck.builder()
                .store(persistedStore())
                .checkResult(NtsLookupResult.UNCONFIRMED)
                .build();

        assertThatThrownBy(() -> storeNtsCheckRepository.saveAndFlush(check))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

}
