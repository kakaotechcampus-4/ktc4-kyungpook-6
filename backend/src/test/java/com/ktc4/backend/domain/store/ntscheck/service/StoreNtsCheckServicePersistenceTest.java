package com.ktc4.backend.domain.store.ntscheck.service;

import com.ktc4.backend.domain.business.dto.BusinessStatus;
import com.ktc4.backend.domain.business.enums.BusinessState;
import com.ktc4.backend.domain.store.entity.Store;
import com.ktc4.backend.domain.store.enums.NtsLookupResult;
import com.ktc4.backend.domain.store.enums.StoreStatus;
import com.ktc4.backend.domain.store.ntscheck.entity.StoreNtsChange;
import com.ktc4.backend.domain.store.ntscheck.entity.StoreNtsCheck;
import com.ktc4.backend.domain.store.ntscheck.repository.StoreNtsChangeRepository;
import com.ktc4.backend.domain.store.ntscheck.repository.StoreNtsCheckRepository;
import com.ktc4.backend.support.PostgresContainerTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * StoreNtsCheckService 를 실제 Postgres 에 붙여서 확인하는 통합 테스트.
 *
 * <p>Mockito 로는 잡을 수 없는 종류를 본다. 배치는 트랜잭션 밖에서 가게를 읽으므로 저장 시점의
 * {@link Store} 는 영속성 컨텍스트에서 분리된(detached) 객체인데, {@link StoreNtsCheck} 가
 * {@code @MapsId} 공유 PK 라 이 상태로 저장하면 Hibernate 가
 * {@code EntityExistsException: detached entity passed to persist} 로 거절한다. 실제로 이 버그가
 * 났었고, 단위 테스트 전부가 green 인 채로 배치만 런타임에 죽는 형태였다 — 그래서 여기서 고정한다.
 */
@Import(StoreNtsCheckService.class)
@DisplayName("StoreNtsCheckService 저장 경로")
class StoreNtsCheckServicePersistenceTest extends PostgresContainerTest {

    @Autowired
    private StoreNtsCheckService storeNtsCheckService;

    @Autowired
    private StoreNtsCheckRepository storeNtsCheckRepository;

    @Autowired
    private StoreNtsChangeRepository storeNtsChangeRepository;

    @Autowired
    private TestEntityManager entityManager;

    // 배치와 같은 조건: 저장한 뒤 영속성 컨텍스트를 비워 detached 상태로 만든다.
    private Store detachedStore(String bizNo) {
        Store store = entityManager.persistAndFlush(Store.builder()
                .name("예시분식")
                .nameNormalized("예시분식")
                .addressRoad("가상특별시 예시구 샘플로 123")
                .addressNormalized("가상특별시예시구샘플로123")
                .status(StoreStatus.OPEN)
                .bizNo(bizNo)
                .build());
        entityManager.clear();
        return store;
    }

    @Test
    @DisplayName("분리된 가게 객체로도 확인 기록과 변경 이력이 실제로 저장된다")
    void savesCheckAndChangeWithDetachedStore() {
        Store store = detachedStore("1234567890");
        LocalDateTime attemptedAt = LocalDateTime.now();

        storeNtsCheckService.markChecked(
                List.of(store),
                Map.of("1234567890", new BusinessStatus("1234567890", BusinessState.CLOSED, LocalDate.of(2026, 3, 1))),
                attemptedAt);
        entityManager.flush();
        entityManager.clear();

        StoreNtsCheck check = storeNtsCheckRepository.findById(store.getStoreId()).orElseThrow();
        assertThat(check.getCheckResult()).isEqualTo(NtsLookupResult.CONFIRMED);
        assertThat(check.getNtsState()).isEqualTo(BusinessState.CLOSED);
        assertThat(check.getNtsClosedAt()).isEqualTo(LocalDate.of(2026, 3, 1));
        assertThat(check.getLastSuccessAt()).isNotNull();

        List<StoreNtsChange> changes = storeNtsChangeRepository.findAll();
        assertThat(changes).hasSize(1);
        assertThat(changes.get(0).getStore().getStoreId()).isEqualTo(store.getStoreId());
        assertThat(changes.get(0).getFromState()).isNull();
        assertThat(changes.get(0).getToState()).isEqualTo(BusinessState.CLOSED);
    }

    @Test
    @DisplayName("같은 상태로 다시 확인하면 기록만 갱신되고 이력은 늘지 않는다")
    void updatesCheckWithoutNewChangeWhenStateUnchanged() {
        Store store = detachedStore("1234567890");
        Map<String, BusinessStatus> statuses =
                Map.of("1234567890", new BusinessStatus("1234567890", BusinessState.ACTIVE, null));
        LocalDateTime firstRun = LocalDateTime.now().minusDays(1);

        storeNtsCheckService.markChecked(List.of(store), statuses, firstRun);
        entityManager.flush();
        entityManager.clear();

        LocalDateTime secondRun = LocalDateTime.now();
        storeNtsCheckService.markChecked(List.of(store), statuses, secondRun);
        entityManager.flush();
        entityManager.clear();

        StoreNtsCheck check = storeNtsCheckRepository.findById(store.getStoreId()).orElseThrow();
        assertThat(check.getLastAttemptAt()).isEqualToIgnoringNanos(secondRun);
        assertThat(storeNtsChangeRepository.findAll()).hasSize(1);
    }

    @Test
    @DisplayName("조회에 실패해도 이전에 확인한 국세청 상태는 DB 에 그대로 남는다")
    void keepsPreviousStateAfterFailedLookup() {
        Store store = detachedStore("1234567890");
        LocalDateTime firstRun = LocalDateTime.now().minusDays(1);
        storeNtsCheckService.markChecked(
                List.of(store),
                Map.of("1234567890", new BusinessStatus("1234567890", BusinessState.ACTIVE, null)),
                firstRun);
        entityManager.flush();
        entityManager.clear();

        storeNtsCheckService.markUnconfirmed(List.of(store), LocalDateTime.now());
        entityManager.flush();
        entityManager.clear();

        StoreNtsCheck check = storeNtsCheckRepository.findById(store.getStoreId()).orElseThrow();
        assertThat(check.getCheckResult()).isEqualTo(NtsLookupResult.UNCONFIRMED);
        assertThat(check.getNtsState()).isEqualTo(BusinessState.ACTIVE);
        assertThat(check.getLastSuccessAt()).isEqualToIgnoringNanos(firstRun);
    }

    @Test
    @DisplayName("사업자번호가 없는 가게도 분리된 객체로 기록된다")
    void savesNoBizNoWithDetachedStore() {
        Store store = detachedStore(null);
        LocalDateTime attemptedAt = LocalDateTime.now();

        storeNtsCheckService.markNoBizNo(List.of(store), attemptedAt);
        entityManager.flush();
        entityManager.clear();

        StoreNtsCheck check = storeNtsCheckRepository.findById(store.getStoreId()).orElseThrow();
        assertThat(check.getCheckResult()).isEqualTo(NtsLookupResult.NO_BIZ_NO);
        assertThat(check.getNtsState()).isNull();
        assertThat(check.getLastAttemptAt()).isEqualToIgnoringNanos(attemptedAt);
    }
}
