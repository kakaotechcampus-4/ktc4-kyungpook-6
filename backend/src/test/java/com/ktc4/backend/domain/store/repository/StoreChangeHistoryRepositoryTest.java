package com.ktc4.backend.domain.store.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ktc4.backend.domain.store.dto.StoreChange;
import com.ktc4.backend.domain.store.entity.Store;
import com.ktc4.backend.domain.store.entity.StoreChangeHistory;
import com.ktc4.backend.domain.store.enums.StoreChangeField;
import com.ktc4.backend.domain.store.enums.StoreChangeSource;
import com.ktc4.backend.domain.store.enums.StoreStatus;
import com.ktc4.backend.support.PostgresContainerTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 변경 기록 표를 실제 Postgres 로 왕복한다 — jsonb 칸에 넣은 JSON 이 같은 뜻으로 돌아오는지, enum 칸이 모든 값을 담는지.
 * 가게 정보는 모두 가짜 값이다.
 */
@DisplayName("StoreChangeHistoryRepository")
class StoreChangeHistoryRepositoryTest extends PostgresContainerTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final LocalDateTime AT = LocalDateTime.of(2026, 10, 16, 10, 0);

    @Autowired
    private StoreChangeHistoryRepository historyRepository;

    @Autowired
    private TestEntityManager entityManager;

    private Store store;

    @BeforeEach
    void setUp() {
        store = entityManager.persistAndFlush(Store.builder()
                .name("예시분식").nameNormalized("예시분식")
                .addressRoad("가상특별시 예시구 샘플로 123").addressNormalized("가상특별시예시구샘플로123")
                .status(StoreStatus.OPEN)
                .build());
    }

    private StoreChangeHistory roundTrip(StoreChangeHistory history) {
        Long id = entityManager.persistAndFlush(history).getStoreChangeHistoryId();
        entityManager.clear();
        return historyRepository.findById(id).orElseThrow();
    }

    @Test
    @DisplayName("jsonb 에 넣은 값이 같은 뜻의 JSON 으로 돌아온다 — 공백·키 순서는 Postgres 가 바꿔도 된다")
    void jsonValuesSurviveRoundTrip() throws Exception {
        String before = "[{\"day\":\"MON\",\"open\":\"11:00\",\"close\":\"24:00\"}]";
        String after = "[{\"day\":\"FRI\",\"open\":\"21:00\",\"close\":\"02:00\"},"
                + "{\"day\":\"SUN\",\"open\":\"10:00\",\"close\":\"14:00\"}]";

        StoreChangeHistory found = roundTrip(StoreChangeHistory.of(store, StoreChangeField.BUSINESS_HOURS,
                StoreChange.taskApply(7L, 50L, AT), before, after));

        assertThat(JSON.readTree(found.getBeforeValue())).isEqualTo(JSON.readTree(before));
        assertThat(JSON.readTree(found.getAfterValue())).isEqualTo(JSON.readTree(after));
        assertThat(found.getStore().getStoreId()).isEqualTo(store.getStoreId());
        assertThat(found.getChangedBy()).isEqualTo(7L);
        assertThat(found.getTaskId()).isEqualTo(50L);
        assertThat(found.getChangedAt()).isEqualTo(AT);
    }

    @Test
    @DisplayName("JSON 문자열 값(전화 등)도 문자열로 돌아오고, 없는 값은 SQL NULL 로 남는다")
    void scalarJsonAndNullSurviveRoundTrip() throws Exception {
        StoreChangeHistory found = roundTrip(StoreChangeHistory.of(store, StoreChangeField.PHONE,
                StoreChange.storeEdit(7L), null, "\"053-123-4567\""));

        assertThat(found.getBeforeValue()).isNull();
        assertThat(JSON.readTree(found.getAfterValue()).asText()).isEqualTo("053-123-4567");
    }

    @ParameterizedTest
    @EnumSource(StoreChangeField.class)
    @DisplayName("항목 칸은 모든 값을 담는다")
    void storesEveryField(StoreChangeField field) {
        assertThat(roundTrip(StoreChangeHistory.of(store, field, StoreChange.storeEdit(7L), null, "\"x\"")).getField())
                .isEqualTo(field);
    }

    @ParameterizedTest
    @EnumSource(StoreChangeSource.class)
    @DisplayName("경로 칸은 모든 값을 담는다")
    void storesEverySource(StoreChangeSource source) {
        Long taskId = source == StoreChangeSource.TASK_APPLY ? 50L : null;
        StoreChange change = new StoreChange(source, 7L, taskId, AT);

        assertThat(roundTrip(StoreChangeHistory.of(store, StoreChangeField.NAME, change, null, "\"x\"")).getSource())
                .isEqualTo(source);
    }
}
