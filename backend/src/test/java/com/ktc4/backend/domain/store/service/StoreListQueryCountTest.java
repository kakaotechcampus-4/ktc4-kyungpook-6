package com.ktc4.backend.domain.store.service;

import com.ktc4.backend.domain.business.enums.BusinessState;
import com.ktc4.backend.domain.store.dto.StoreResponse;
import com.ktc4.backend.domain.store.entity.Store;
import com.ktc4.backend.domain.store.enums.InvestigationExclusionReason;
import com.ktc4.backend.domain.store.enums.NtsLookupResult;
import com.ktc4.backend.domain.store.enums.StoreStatus;
import com.ktc4.backend.domain.store.ntscheck.entity.StoreNtsCheck;
import com.ktc4.backend.global.dto.PageResponse;
import com.ktc4.backend.support.PostgresContainerTest;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 가게 목록 조회가 가게 수와 무관하게 같은 횟수만 묻는지 실제 DB 로 본다.
 *
 * <p>목록에 조사 제외 사유를 담으려면 국세청 확인 기록이 필요하다. 가게마다 그 기록을 따로 물으면
 * 한 페이지(20건)가 21번 묻는 N+1 이 된다 — 담당자가 쓰는 첫 화면이라 그대로 두면 바로 느려진다.
 * 그래서 바깥 조인으로 한 번에 읽는데, 그 약속이 깨지는 건 대개 나중에 지연 로딩이 끼어들 때라
 * <b>쿼리 수를 세어</b> 고정한다.
 *
 * <p>가게 수만 다른 두 번의 조회가 같은 횟수여야 한다는 식으로 본다. 절대 횟수를 박으면 쿼리를 하나
 * 더 쓰는 정당한 변경(예: 집계 한 줄 추가)까지 깨진다.
 */
// 통계를 켜야 쿼리 수를 셀 수 있다. 다른 테스트 컨텍스트에 영향을 주지 않도록 이 클래스에서만 켠다
@DataJpaTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@Import({StoreService.class, StoreChangeHistoryService.class})
@DisplayName("StoreService.getStores 쿼리 수")
class StoreListQueryCountTest extends PostgresContainerTest {

    private static final LocalDateTime CHECKED_AT = LocalDateTime.of(2026, 9, 22, 3, 0);

    @Autowired
    private StoreService storeService;

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    private Statistics statistics;

    @BeforeEach
    void clearStatistics() {
        statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();
    }

    private Store persistStore(String name, StoreStatus status) {
        return entityManager.persistAndFlush(Store.builder()
                .name(name).nameNormalized(name)
                .addressRoad("가상특별시 예시구 샘플로 123").addressNormalized("가상특별시예시구샘플로123")
                .status(status).bizNo("1234567890")
                .build());
    }

    /** 국세청과 상태가 같은 영업 중 가게 — 조사 대상이라 제외 사유가 없다. */
    private void persistCheckedOpenStore(String name) {
        Store store = persistStore(name, StoreStatus.OPEN);
        entityManager.persistAndFlush(StoreNtsCheck.builder()
                .store(store).bizNo(store.getBizNo())
                .checkResult(NtsLookupResult.CONFIRMED).ntsState(BusinessState.ACTIVE)
                .lastAttemptAt(CHECKED_AT).lastSuccessAt(CHECKED_AT)
                .build());
    }

    private long queryCountOfGetStores() {
        entityManager.clear();
        statistics.clear();
        storeService.getStores(0, 20);
        return statistics.getPrepareStatementCount();
    }

    @Test
    @DisplayName("가게가 늘어도 묻는 횟수는 그대로다 — 가게마다 국세청 기록을 따로 물으면 N+1 이다")
    void doesNotAskPerStore() {
        persistCheckedOpenStore("가게1");
        long withOneStore = queryCountOfGetStores();

        for (int i = 2; i <= 12; i++) {
            persistCheckedOpenStore("가게" + i);
        }
        long withTwelveStores = queryCountOfGetStores();

        // 측정값은 둘 다 2 다 — 목록 한 번 + 최근 변경 시각 한 번. 가게마다 묻는다면 12건에서 13 이 된다
        assertThat(withTwelveStores).isEqualTo(withOneStore);
    }

    @Test
    @DisplayName("국세청 기록이 없는 가게도 목록에 남고 NO_NTS_CHECK 로 빠진다고 알려준다")
    void keepsNotYetCheckedStoreInTheList() {
        persistCheckedOpenStore("대조된 가게");
        persistStore("대조 안 된 가게", StoreStatus.OPEN);
        entityManager.clear();

        PageResponse<StoreResponse> response = storeService.getStores(0, 20);

        List<StoreResponse> excluded = response.content().stream()
                .filter(StoreResponse::investigationExcluded)
                .toList();
        assertThat(response.content()).hasSize(2);
        assertThat(excluded).singleElement()
                .satisfies(store -> {
                    assertThat(store.name()).isEqualTo("대조 안 된 가게");
                    assertThat(store.investigationExclusionReason())
                            .isEqualTo(InvestigationExclusionReason.NO_NTS_CHECK);
                });
    }
}
