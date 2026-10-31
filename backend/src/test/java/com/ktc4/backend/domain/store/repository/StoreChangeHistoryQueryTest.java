package com.ktc4.backend.domain.store.repository;

import com.ktc4.backend.domain.store.dto.StoreChange;
import com.ktc4.backend.domain.store.dto.StoreLastChange;
import com.ktc4.backend.domain.store.entity.Store;
import com.ktc4.backend.domain.store.entity.StoreChangeHistory;
import com.ktc4.backend.domain.store.enums.StoreChangeField;
import com.ktc4.backend.domain.store.enums.StoreStatus;
import com.ktc4.backend.support.PostgresContainerTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * 가게별 마지막 변경 시각 조회를 실제 DB 로 본다.
 *
 * <p>화면이 "마지막 확인"(사람이 직접 본 날)과 "최근 변경"(값이 바뀐 날)을 따로 보여주기 위한
 * 값이다 — 수정안을 반영해도 확인일은 오르지 않아서, 날짜가 하나뿐이면 담당자에게
 * "반영했는데 왜 그대로지" 로 보인다(11주차 멘토 리뷰).
 *
 * <p>가게 정보는 모두 가짜 값이다.
 */
@DisplayName("StoreChangeHistoryRepository.findLastChangedAtByStoreIds")
class StoreChangeHistoryQueryTest extends PostgresContainerTest {

    private static final LocalDateTime BASE = LocalDateTime.of(2026, 9, 2, 14, 30);

    @Autowired
    private StoreChangeHistoryRepository historyRepository;

    @Autowired
    private TestEntityManager entityManager;

    private Store store(String name) {
        return entityManager.persistAndFlush(Store.builder()
                .name(name).nameNormalized(name)
                .addressRoad("가상특별시 예시구 샘플로 123").addressNormalized("가상특별시예시구샘플로123")
                .status(StoreStatus.OPEN)
                .build());
    }

    private void record(Store store, LocalDateTime changedAt) {
        StoreChange change = new StoreChange(
                StoreChange.storeEdit(7L).source(), 7L, null, changedAt);
        entityManager.persistAndFlush(StoreChangeHistory.of(
                store, StoreChangeField.NAME, change, "\"전\"", "\"후\""));
    }

    @Test
    @DisplayName("줄이 여럿이면 가장 늦은 시각을 돌려준다")
    void returnsTheLatest() {
        Store store = store("예시분식");
        record(store, BASE);
        record(store, BASE.plusDays(3));
        record(store, BASE.plusDays(1));

        assertThat(historyRepository.findLastChangedAtByStoreIds(List.of(store.getStoreId())))
                .singleElement()
                .extracting(StoreLastChange::lastChangedAt)
                .isEqualTo(BASE.plusDays(3));
    }

    @Test
    @DisplayName("가게마다 따로 묶는다 — 한 번의 조회로 여러 가게를 본다")
    void groupsByStore() {
        Store first = store("예시분식");
        Store second = store("예시치킨");
        record(first, BASE);
        record(second, BASE.plusDays(5));

        assertThat(historyRepository.findLastChangedAtByStoreIds(
                List.of(first.getStoreId(), second.getStoreId())))
                .extracting(StoreLastChange::storeId, StoreLastChange::lastChangedAt)
                .containsExactlyInAnyOrder(
                        tuple(first.getStoreId(), BASE),
                        tuple(second.getStoreId(), BASE.plusDays(5)));
    }

    @Test
    @DisplayName("바뀐 적이 없는 가게는 결과에 없다 — 날짜를 지어내지 않는다")
    void omitsStoresWithoutHistory() {
        Store changed = store("예시분식");
        Store untouched = store("예시치킨");
        record(changed, BASE);

        assertThat(historyRepository.findLastChangedAtByStoreIds(
                List.of(changed.getStoreId(), untouched.getStoreId())))
                .extracting(StoreLastChange::storeId)
                .containsExactly(changed.getStoreId());
    }

    @Test
    @DisplayName("묻지 않은 가게는 돌려주지 않는다")
    void ignoresOtherStores() {
        Store asked = store("예시분식");
        Store other = store("예시치킨");
        record(asked, BASE);
        record(other, BASE.plusDays(1));

        assertThat(historyRepository.findLastChangedAtByStoreIds(List.of(asked.getStoreId())))
                .extracting(StoreLastChange::storeId)
                .containsExactly(asked.getStoreId());
    }
}
