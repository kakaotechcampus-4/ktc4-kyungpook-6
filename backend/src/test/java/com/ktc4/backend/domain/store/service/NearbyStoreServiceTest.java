package com.ktc4.backend.domain.store.service;

import com.ktc4.backend.domain.store.dto.NearbyStoreResponse;
import com.ktc4.backend.domain.store.entity.Store;
import com.ktc4.backend.domain.store.entity.StoreBusinessHour;
import com.ktc4.backend.domain.store.entity.StoreMenu;
import com.ktc4.backend.domain.store.enums.BusinessDay;
import com.ktc4.backend.domain.store.enums.OpenMode;
import com.ktc4.backend.domain.store.enums.StoreStatus;
import com.ktc4.backend.support.PostgresContainerTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * 주변 가게 찾기를 실제 Postgres 로 본다 — 사각형 쿼리·검색 쿼리(LIKE 이스케이프·메뉴 EXISTS)는 DB 가 해석해야 맞는지 안다.
 * V7 마이그레이션으로 만든 영업 상태 칸도 여기서 읽힌다. 값은 모두 지어낸 것이다.
 */
@Import(NearbyStoreService.class)
@DisplayName("NearbyStoreService (Postgres)")
class NearbyStoreServiceTest extends PostgresContainerTest {

    private static final double LAT = 35.8884;
    private static final double LNG = 128.6107;
    // 위도 1도 ≈ 111.2km — 북쪽으로 meters 만큼 옮긴 위도
    private static double north(int meters) {
        return LAT + meters / 111_195.0;
    }

    @Autowired
    private NearbyStoreService service;

    @Autowired
    private TestEntityManager entityManager;

    private Store store(String name, Double lat, Double lng, StoreStatus status) {
        return entityManager.persistAndFlush(Store.builder()
                .name(name)
                .nameNormalized(name.replace(" ", ""))
                .addressRoad("가상특별시 예시구 샘플로 123")
                .addressNormalized("가상특별시예시구샘플로123")
                .lat(lat).lng(lng)
                .status(status)
                .phone("053-000-0000")
                .build());
    }

    private List<NearbyStoreResponse> find(int radius, String query) {
        entityManager.clear();
        return service.findNearby(LAT, LNG, radius, query);
    }

    @Test
    @DisplayName("반경 안의 가게만 가까운 순으로, 거리와 함께 — 폐업·휴업·좌표 없는 가게는 빠진다")
    void returnsStoresInRadiusByDistance() {
        Store far = store("먼분식", north(1500), LNG, StoreStatus.OPEN);
        Store near = store("가까운분식", north(300), LNG, StoreStatus.UNKNOWN);
        store("반경밖분식", north(2500), LNG, StoreStatus.OPEN);
        store("폐업분식", north(100), LNG, StoreStatus.CLOSED);
        store("휴업분식", north(100), LNG, StoreStatus.SUSPENDED);
        store("좌표없는분식", null, null, StoreStatus.OPEN);

        List<NearbyStoreResponse> result = find(2000, null);

        assertThat(result).extracting(NearbyStoreResponse::storeId).containsExactly(near.getStoreId(), far.getStoreId());
        assertThat(result.get(0).distanceMeters()).isBetween(299, 301);
        assertThat(result.get(1).distanceMeters()).isBetween(1499, 1501);
        assertThat(result.get(0).lat()).isEqualTo(north(300));
        assertThat(result.get(0).companion()).isEqualTo("NONE");
    }

    @Test
    @DisplayName("영업 상태·영업시간(요일 순)·지우지 않은 메뉴를 함께 준다")
    void includesOpenSettingHoursAndLiveMenus() {
        Store store = store("예시분식", north(200), LNG, StoreStatus.OPEN);
        store.changeOpenSetting(OpenMode.MANUAL, false);
        entityManager.persist(StoreBusinessHour.of(store, BusinessDay.SUN, 600, 1200));
        entityManager.persist(StoreBusinessHour.of(store, BusinessDay.MON, 1020, 1260));
        entityManager.persist(StoreBusinessHour.of(store, BusinessDay.MON, 660, 900));
        entityManager.persist(StoreMenu.of(store, "떡볶이", true));
        StoreMenu deleted = entityManager.persist(StoreMenu.of(store, "지운메뉴", true));
        deleted.markDeleted(LocalDateTime.of(2026, 11, 1, 12, 0));
        entityManager.persist(StoreMenu.of(store, "김밥", false));
        entityManager.flush();

        NearbyStoreResponse result = find(2000, null).get(0);

        assertThat(result.openMode()).isEqualTo(OpenMode.MANUAL);
        assertThat(result.manualOpen()).isFalse();
        assertThat(result.businessHours()).extracting("day", "open", "close").containsExactly(
                tuple(BusinessDay.MON, "11:00", "15:00"), tuple(BusinessDay.MON, "17:00", "21:00"),
                tuple(BusinessDay.SUN, "10:00", "20:00"));
        assertThat(result.menus()).extracting("name", "onSale").containsExactly(tuple("떡볶이", true), tuple("김밥", false));
    }

    @Test
    @DisplayName("새로 넣은 가게는 영업시간대로·켜짐이 기본이다 — V7 기본값과 엔티티 기본값이 같다")
    void newStoreDefaultsToSchedule() {
        store("예시분식", north(200), LNG, StoreStatus.OPEN);

        NearbyStoreResponse result = find(2000, null).get(0);

        assertThat(result.openMode()).isEqualTo(OpenMode.SCHEDULE);
        assertThat(result.manualOpen()).isTrue();
    }

    @Test
    @DisplayName("검색어는 상호명(띄어 써도)·지우지 않은 메뉴 이름에서 찾는다")
    void searchesByNameAndMenu() {
        Store byName = store("예시분식", north(200), LNG, StoreStatus.OPEN);
        Store byMenu = store("맛나치킨", north(400), LNG, StoreStatus.OPEN);
        entityManager.persist(StoreMenu.of(byMenu, "치즈 떡볶이", true));
        Store byDeletedMenu = store("행복도시락", north(600), LNG, StoreStatus.OPEN);
        StoreMenu deleted = entityManager.persist(StoreMenu.of(byDeletedMenu, "떡볶이", true));
        deleted.markDeleted(LocalDateTime.of(2026, 11, 1, 12, 0));
        entityManager.flush();

        assertThat(find(2000, "떡볶이")).extracting(NearbyStoreResponse::storeId).containsExactly(byMenu.getStoreId());
        assertThat(find(2000, "예시 분식")).extracting(NearbyStoreResponse::storeId).containsExactly(byName.getStoreId());
        assertThat(find(2000, "치킨")).extracting(NearbyStoreResponse::storeId).containsExactly(byMenu.getStoreId());
    }

    @Test
    @DisplayName("검색어의 % · _ 는 글자 그대로 찾는다 — 모든 가게와 맞지 않는다")
    void escapesLikeWildcards() {
        store("예시분식", north(200), LNG, StoreStatus.OPEN);

        assertThat(find(2000, "%")).isEmpty();
        assertThat(find(2000, "_")).isEmpty();
    }

    @Test
    @DisplayName("DB 에서 읽는 수에 상한이 있어도 가장 가까운 곳부터 나온다 — 먼저 넣은 먼 가게가 가까운 가게를 밀어내지 않는다")
    void capKeepsNearestStores() {
        // 먼 가게를 먼저, 남쪽(위도가 작은 쪽)에 넣는다 — DB 가 정렬 없이 자르면 넣은 순서로 읽든 (lat, lng) 인덱스 순서로
        // 읽든 이 가게들이 먼저 읽혀 가까운 가게가 잘린다
        for (int i = 0; i < NearbyStoreService.CANDIDATE_LIMIT; i++) {
            store("먼가게" + i, north(-1000 - i), LNG, StoreStatus.OPEN);
        }
        List<Long> nearest = new ArrayList<>();
        for (int i = 0; i < NearbyStoreService.MAX_RESULTS; i++) {
            nearest.add(store("가까운가게" + i, north(100 + i * 5), LNG, StoreStatus.OPEN).getStoreId());
        }

        assertThat(find(2000, null)).extracting(NearbyStoreResponse::storeId).containsExactlyElementsOf(nearest);
        assertThat(find(2000, "가게")).extracting(NearbyStoreResponse::storeId).containsExactlyElementsOf(nearest);
    }

    @Test
    @DisplayName("반경 안에 가게가 없으면 빈 목록")
    void emptyWhenNothingNearby() {
        store("먼분식", north(3000), LNG, StoreStatus.OPEN);

        assertThat(find(2000, null)).isEmpty();
    }
}
