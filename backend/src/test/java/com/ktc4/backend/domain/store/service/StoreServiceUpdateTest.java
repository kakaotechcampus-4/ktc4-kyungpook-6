package com.ktc4.backend.domain.store.service;

import com.ktc4.backend.domain.member.service.StoreAccessService;
import com.ktc4.backend.domain.store.dto.StoreChange;
import com.ktc4.backend.domain.store.dto.StoreUpdateRequest;
import com.ktc4.backend.domain.store.entity.Store;
import com.ktc4.backend.domain.store.entity.StoreBusinessHour;
import com.ktc4.backend.domain.store.entity.StoreMenu;
import com.ktc4.backend.domain.store.enums.BusinessDay;
import com.ktc4.backend.domain.store.enums.StoreChangeField;
import com.ktc4.backend.domain.store.enums.StoreChangeSource;
import com.ktc4.backend.domain.store.enums.StoreStatus;
import com.ktc4.backend.support.PostgresContainerTest;
import com.ktc4.backend.support.StoreChangeHistoryRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static com.ktc4.backend.support.StoreChangeHistoryRow.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * 관리자 가게 수정이 남기는 변경 기록을 실제 DB 로 본다 — 바뀐 칸만, 누가·어느 경로로, 바뀌기 전·후 값.
 * 수정 로직 자체(정규화 등)는 {@link StoreServiceTest} 가, 잠금·되돌리기는 {@link StoreChangeHistoryCommitTest} 가 본다.
 * 가게 정보는 모두 가짜 값이다.
 */
// 가게 관리 테스트와 같은 빈 묶음 — 스프링 테스트 컨텍스트(와 DB 연결 풀)를 함께 쓴다
@Import({StoreManagementService.class, StoreAccessService.class, StoreService.class, StoreChangeHistoryService.class})
@DisplayName("StoreService.updateStore 변경 기록")
class StoreServiceUpdateTest extends PostgresContainerTest {

    private static final long ADMIN_ID = 7L;

    @Autowired
    private StoreService storeService;

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Store store;

    @BeforeEach
    void setUp() {
        store = entityManager.persistAndFlush(Store.builder()
                .name("예시분식").nameNormalized("예시분식")
                .addressRoad("가상특별시 예시구 샘플로 123").addressNormalized("가상특별시예시구샘플로123")
                .status(StoreStatus.OPEN).phone("053-000-0001")
                .build());
        // 관리자 수정이 건드리지 않는 영업시간·메뉴도 둔다 — 전·후 스냅숏이 둘 다 같은 값을 읽어야 기록에 안 남는다
        entityManager.persistAndFlush(StoreBusinessHour.of(store, BusinessDay.MON, 660, 1260));
        entityManager.persistAndFlush(StoreMenu.of(store, "김밥", true));
    }

    private List<StoreChangeHistoryRow> histories() {
        entityManager.flush();
        return StoreChangeHistoryRow.of(jdbcTemplate, store.getStoreId());
    }

    private void update(String name, String addressRoad, String phone, StoreStatus status) {
        storeService.updateStore(store.getStoreId(), new StoreUpdateRequest(name, addressRoad, phone, status),
                StoreChange.storeEdit(ADMIN_ID));
    }

    @Test
    @DisplayName("이름만 보내면 이름 한 줄 — 관리자 수정 경로·관리자 ID, 바뀌기 전·후 값")
    void recordsOnlyChangedField() {
        update("새이름", null, null, null);

        assertThat(histories()).singleElement().satisfies(row -> {
            assertThat(row.field()).isEqualTo(StoreChangeField.NAME);
            assertThat(row.source()).isEqualTo(StoreChangeSource.STORE_EDIT);
            assertThat(row.changedBy()).isEqualTo(ADMIN_ID);
            assertThat(row.taskId()).isNull();
            assertThat(row.before()).isEqualTo(json("\"예시분식\""));
            assertThat(row.after()).isEqualTo(json("\"새이름\""));
        });
    }

    @Test
    @DisplayName("네 칸을 모두 바꾸면 네 줄 — 같은 요청이라 시각이 같다")
    void recordsEveryChangedField() {
        update("새이름", "가상특별시 예시구 샘플로 456", "053-999-0000", StoreStatus.CLOSED);

        List<StoreChangeHistoryRow> rows = histories();
        assertThat(rows).extracting(StoreChangeHistoryRow::field, StoreChangeHistoryRow::before, StoreChangeHistoryRow::after)
                .containsExactly(
                        tuple(StoreChangeField.NAME, json("\"예시분식\""), json("\"새이름\"")),
                        tuple(StoreChangeField.ADDRESS_ROAD, json("\"가상특별시 예시구 샘플로 123\""),
                                json("\"가상특별시 예시구 샘플로 456\"")),
                        tuple(StoreChangeField.PHONE, json("\"053-000-0001\""), json("\"053-999-0000\"")),
                        tuple(StoreChangeField.STATUS, json("\"OPEN\""), json("\"CLOSED\"")));
        assertThat(rows).extracting(StoreChangeHistoryRow::changedAt).containsOnly(rows.get(0).changedAt());
    }

    @Test
    @DisplayName("지금과 같은 값으로 보내면 아무것도 남기지 않는다")
    void sameValuesLeaveNoRecord() {
        update("예시분식", "가상특별시 예시구 샘플로 123", "053-000-0001", StoreStatus.OPEN);

        assertThat(histories()).isEmpty();
    }

    @Test
    @DisplayName("전화를 지우면(빈 문자열) 한 줄 — 뒤 값은 없음(NULL)")
    void clearingPhoneRecordsAbsentAfter() {
        update(null, null, "", null);

        assertThat(histories()).singleElement().satisfies(row -> {
            assertThat(row.field()).isEqualTo(StoreChangeField.PHONE);
            assertThat(row.before()).isEqualTo(json("\"053-000-0001\""));
            assertThat(row.after()).isNull();
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "  "})
    @DisplayName("전화가 없는 가게에 빈 전화를 보내도 기록하지 않는다 — 둘 다 \"없음\"이다")
    void blankPhoneOnStoreWithoutPhoneLeavesNoRecord(String blank) {
        Store noPhone = entityManager.persistAndFlush(Store.builder()
                .name("전화없는가게").nameNormalized("전화없는가게")
                .addressRoad("가상특별시 예시구 샘플로 789").addressNormalized("가상특별시예시구샘플로789")
                .status(StoreStatus.OPEN)
                .build());

        storeService.updateStore(noPhone.getStoreId(), new StoreUpdateRequest(null, null, blank, null),
                StoreChange.storeEdit(ADMIN_ID));

        entityManager.flush();
        assertThat(StoreChangeHistoryRow.of(jdbcTemplate, noPhone.getStoreId())).isEmpty();
    }
}
