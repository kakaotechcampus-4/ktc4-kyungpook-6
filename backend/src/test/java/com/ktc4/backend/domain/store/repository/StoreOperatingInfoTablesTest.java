package com.ktc4.backend.domain.store.repository;

import com.ktc4.backend.domain.store.entity.Store;
import com.ktc4.backend.domain.store.entity.StoreBusinessHour;
import com.ktc4.backend.domain.store.entity.StoreMenu;
import com.ktc4.backend.domain.store.enums.BusinessDay;
import com.ktc4.backend.domain.store.enums.StoreStatus;
import com.ktc4.backend.support.PostgresContainerTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * 가게의 영업시간·메뉴 표가 실제 Postgres 에 맞게 만들어지는지 본다 — 왕복, 검사 조건, 외래 키.
 *
 * <p>검사 조건은 엔티티를 거치지 않고 SQL 로 직접 넣어 본다. DB 가 마지막으로 막아 주는지가 보고 싶은 것이라서다.
 * 가게 정보는 모두 가짜 값이다.
 */
@DisplayName("가게 영업시간·메뉴 표")
class StoreOperatingInfoTablesTest extends PostgresContainerTest {

    private static final String CHECK_VIOLATION = "23514";
    private static final String FOREIGN_KEY_VIOLATION = "23503";

    @Autowired
    private StoreBusinessHourRepository businessHourRepository;

    @Autowired
    private StoreMenuRepository menuRepository;

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Store store;

    @BeforeEach
    void setUp() {
        store = entityManager.persistAndFlush(Store.builder()
                .name("예시분식")
                .nameNormalized("예시분식")
                .addressRoad("가상특별시 예시구 샘플로 123")
                .addressNormalized("가상특별시예시구샘플로123")
                .status(StoreStatus.OPEN)
                .build());
    }

    @ParameterizedTest
    @EnumSource(BusinessDay.class)
    @DisplayName("모든 요일이 요일 칸에 들어갔다 그대로 읽힌다")
    void roundTripsEveryDay(BusinessDay day) {
        Long id = entityManager.persistAndFlush(StoreBusinessHour.of(store, day, 0, 1440)).getBusinessHourId();
        entityManager.clear();

        StoreBusinessHour read = businessHourRepository.findById(id).orElseThrow();
        assertThat(read.getDay()).isEqualTo(day);
        assertThat(read.getOpenMinute()).isZero();
        assertThat(read.getCloseMinute()).isEqualTo(1440);
        assertThat(read.getStore().getStoreId()).isEqualTo(store.getStoreId());
    }

    @ParameterizedTest(name = "여는 {0}분 ~ 닫는 {1}분 → 받음 {2}")
    @CsvSource({
            "-1, 600, false", "1440, 600, false", "0, 600, true", "1439, 600, true",
            "600, 0, false", "600, 1441, false", "600, 1, true", "600, 1440, true",
            "600, 600, false",
            "1260, 120, true"})
    @DisplayName("DB 검사 조건 — 여는 0~1439, 닫는 1~1440, 여닫는 시간이 같으면 거절, 자정 넘김(닫는 < 여는)은 받음")
    void checkConstraintBoundaries(int openMinute, int closeMinute, boolean accepted) {
        Throwable thrown = catchThrowable(() -> jdbcTemplate.update(
                "insert into store_business_hour (store_id, day, open_minute, close_minute, created_at, updated_at)"
                        + " values (?, 'MON', ?, ?, now(), now())",
                store.getStoreId(), openMinute, closeMinute));

        if (accepted) {
            assertThat(thrown).isNull();
        } else {
            assertThat(sqlStateOf(thrown)).isEqualTo(CHECK_VIOLATION);
        }
    }

    @Test
    @DisplayName("없는 가게를 가리키는 영업시간은 DB 가 거절한다")
    void rejectsUnknownStore() {
        Throwable thrown = catchThrowable(() -> jdbcTemplate.update(
                "insert into store_business_hour (store_id, day, open_minute, close_minute, created_at, updated_at)"
                        + " values (?, 'MON', 600, 1200, now(), now())",
                store.getStoreId() + 1_000_000));

        assertThat(sqlStateOf(thrown)).isEqualTo(FOREIGN_KEY_VIOLATION);
    }

    @Test
    @DisplayName("메뉴는 넣은 순서(menuId 순)대로 읽히고, 처음에는 판매 중이다 — 이름 순이 아니다")
    void menusKeepInsertionOrder() {
        menuRepository.saveAll(List.of(
                StoreMenu.onSale(store, "나물비빔밥"), StoreMenu.onSale(store, "가락국수"), StoreMenu.onSale(store, "다슬기국")));
        entityManager.flush();
        entityManager.clear();

        List<StoreMenu> read = menuRepository.findAll().stream()
                .filter(menu -> menu.getStore().getStoreId().equals(store.getStoreId()))
                .sorted((a, b) -> Long.compare(a.getMenuId(), b.getMenuId()))
                .toList();
        assertThat(read).extracting(StoreMenu::getName).containsExactly("나물비빔밥", "가락국수", "다슬기국");
        assertThat(read).allMatch(StoreMenu::isOnSale);
    }

    @Test
    @DisplayName("메뉴의 지운 시각이 그대로 왕복하고, 지운 메뉴는 '메뉴가 있나' 에서 세지 않는다")
    void roundTripsDeletedAt() {
        StoreMenu menu = StoreMenu.onSale(store, "옛 메뉴");
        menu.markDeleted(LocalDateTime.of(2026, 1, 1, 9, 0, 1));
        Long id = entityManager.persistAndFlush(menu).getMenuId();
        entityManager.clear();

        assertThat(menuRepository.findById(id).orElseThrow().getDeletedAt()).isEqualTo(LocalDateTime.of(2026, 1, 1, 9, 0, 1));
        assertThat(menuRepository.existsByStoreStoreIdAndDeletedAtIsNull(store.getStoreId())).isFalse();
    }

    @Test
    @DisplayName("가게에 영업시간·메뉴가 있는지는 그 가게 것만 센다")
    void existsCountsOnlyThatStore() {
        Store other = entityManager.persistAndFlush(Store.builder()
                .name("다른가게").nameNormalized("다른가게")
                .addressRoad("가상특별시 예시구 샘플로 456").addressNormalized("가상특별시예시구샘플로456")
                .status(StoreStatus.OPEN).build());
        entityManager.persistAndFlush(StoreBusinessHour.of(other, BusinessDay.MON, 600, 1200));
        entityManager.persistAndFlush(StoreMenu.onSale(other, "김치찌개"));
        entityManager.clear();

        assertThat(businessHourRepository.existsByStoreStoreId(store.getStoreId())).isFalse();
        assertThat(menuRepository.existsByStoreStoreIdAndDeletedAtIsNull(store.getStoreId())).isFalse();
        assertThat(businessHourRepository.existsByStoreStoreId(other.getStoreId())).isTrue();
        assertThat(menuRepository.existsByStoreStoreIdAndDeletedAtIsNull(other.getStoreId())).isTrue();
    }

    private static String sqlStateOf(Throwable thrown) {
        for (Throwable cause = thrown; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sqlException) {
                return sqlException.getSQLState();
            }
        }
        return null;
    }
}
