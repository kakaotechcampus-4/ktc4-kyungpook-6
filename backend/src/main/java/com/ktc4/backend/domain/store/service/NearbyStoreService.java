package com.ktc4.backend.domain.store.service;

import com.ktc4.backend.domain.store.dto.BusinessHourResponse;
import com.ktc4.backend.domain.store.dto.NearbyStoreMenuResponse;
import com.ktc4.backend.domain.store.dto.NearbyStoreResponse;
import com.ktc4.backend.domain.store.entity.Store;
import com.ktc4.backend.domain.store.entity.StoreBusinessHour;
import com.ktc4.backend.domain.store.entity.StoreMenu;
import com.ktc4.backend.domain.store.enums.StoreStatus;
import com.ktc4.backend.domain.store.repository.StoreBusinessHourRepository;
import com.ktc4.backend.domain.store.repository.StoreMenuRepository;
import com.ktc4.backend.domain.store.repository.StoreRepository;
import com.ktc4.backend.domain.store.util.BusinessTimes;
import com.ktc4.backend.domain.store.util.GeoDistance;
import com.ktc4.backend.domain.store.util.StoreNormalizer;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 아동 앱의 주변 가게 — 내 위치에서 반경 안의 가게를 가까운 순으로, 거리와 함께 돌려준다(PROMPT-185).
 *
 * <p>DB 에서 위경도 사각형으로 먼저 거르고(인덱스) 가까운 순으로 {@value #CANDIDATE_LIMIT}곳까지만 읽은 뒤, 실제 거리로
 * 다시 걸러 정렬한다. 폐업·휴업 가게와 좌표가
 * 없는 가게는 빠진다. 검색어를 주면 상호명이나 메뉴 이름에 그 말이 들어간 가게만 남긴다.
 *
 * <p>영업중·쉬는중은 계산하지 않는다 — 앱이 영업 상태 방식·켜고 끈 값·영업시간으로 기기 시각 기준으로 계산한다.
 */
@Service
@RequiredArgsConstructor
public class NearbyStoreService {

    /** 한 번에 돌려주는 가게 수 상한 — 가까운 순으로 자른다. */
    static final int MAX_RESULTS = 100;
    // DB 에서 읽는 가게 수 상한. DB 는 평면 근사로, 여기서는 하버사인(반올림한 m)으로 정렬해 두 순서가 경계에서 어긋날 수
    // 있다 — 결과 수의 두 배를 읽어 그 몫을 둔다. 사각형 모서리(반경 밖)도 가까운 순으로 뒤에 붙어 이 안에서 걸러진다.
    static final int CANDIDATE_LIMIT = MAX_RESULTS * 2;
    // 폐업·휴업 가게는 아이가 찾아가도 밥을 먹을 수 없다. 확인 전(UNKNOWN)은 남긴다 — 들어온 가게 대부분이 그 상태다
    private static final List<StoreStatus> EXCLUDED_STATUSES = List.of(StoreStatus.CLOSED, StoreStatus.SUSPENDED);

    private final StoreRepository storeRepository;
    private final StoreBusinessHourRepository businessHourRepository;
    private final StoreMenuRepository menuRepository;

    /**
     * 주변 가게를 가까운 순으로 찾는다.
     *
     * <p>가게·영업시간·메뉴를 쿼리 세 번으로 읽는다. 그 사이 점주 저장이 커밋돼 옛 영업시간과 새 메뉴가 섞이지 않게, 한
     * 시점의 스냅숏으로 읽는다(가게 상세와 같다).
     *
     * @param lat          내 위도
     * @param lng          내 경도
     * @param radiusMeters 반경(m)
     * @param query        검색어(상호명·메뉴 이름). 비었으면 반경 안의 가게 전부
     * @return 가까운 순(거리가 같으면 가게 ID 순), 최대 {@value #MAX_RESULTS}곳
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<NearbyStoreResponse> findNearby(double lat, double lng, int radiusMeters, String query) {
        GeoDistance.Box box = GeoDistance.boxAround(lat, lng, radiusMeters);
        double cos = Math.cos(Math.toRadians(lat));
        double lngWeight = cos * cos;
        List<Store> candidates = query == null || query.isBlank()
                ? storeRepository.findInBox(box.minLat(), box.maxLat(), box.minLng(), box.maxLng(),
                        lat, lng, lngWeight, EXCLUDED_STATUSES, Limit.of(CANDIDATE_LIMIT))
                : search(box, lat, lng, lngWeight, query.strip());

        List<Found> found = candidates.stream()
                .map(store -> new Found(store,
                        (int) Math.round(GeoDistance.meters(lat, lng, store.getLat(), store.getLng()))))
                .filter(candidate -> candidate.distanceMeters() <= radiusMeters)
                .sorted(Comparator.comparingInt(Found::distanceMeters)
                        .thenComparing(candidate -> candidate.store().getStoreId()))
                .limit(MAX_RESULTS)
                .toList();
        if (found.isEmpty()) {
            return List.of();
        }

        List<Long> storeIds = found.stream().map(candidate -> candidate.store().getStoreId()).toList();
        Map<Long, List<StoreBusinessHour>> hoursByStore = businessHourRepository.findByStoreStoreIdIn(storeIds).stream()
                .collect(Collectors.groupingBy(hour -> hour.getStore().getStoreId()));
        Map<Long, List<StoreMenu>> menusByStore = menuRepository
                .findByStoreStoreIdInAndDeletedAtIsNullOrderByMenuIdAsc(storeIds).stream()
                .collect(Collectors.groupingBy(menu -> menu.getStore().getStoreId()));

        return found.stream()
                .map(candidate -> toResponse(candidate,
                        hoursByStore.getOrDefault(candidate.store().getStoreId(), List.of()),
                        menusByStore.getOrDefault(candidate.store().getStoreId(), List.of())))
                .toList();
    }

    private List<Store> search(GeoDistance.Box box, double lat, double lng, double lngWeight, String query) {
        String rawPattern = containsPattern(query.toLowerCase(Locale.ROOT));
        String normalized = StoreNormalizer.normalizeName(query);
        // 정규화하면 비는 검색어(법인표기·기호뿐)는 정규화 상호명으로는 보지 않는다 — 빈 패턴은 모든 가게와 맞는다
        String normalizedPattern = normalized.isEmpty() ? rawPattern : containsPattern(normalized);
        return storeRepository.searchInBox(box.minLat(), box.maxLat(), box.minLng(), box.maxLng(),
                lat, lng, lngWeight, EXCLUDED_STATUSES, rawPattern, normalizedPattern, Limit.of(CANDIDATE_LIMIT));
    }

    // LIKE 의 와일드카드를 이스케이프하고 앞뒤를 % 로 감싼다 — 검색어의 "%" 가 모든 가게와 맞지 않게
    private static String containsPattern(String value) {
        String escaped = value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        return "%" + escaped + "%";
    }

    private static NearbyStoreResponse toResponse(Found candidate, List<StoreBusinessHour> hours, List<StoreMenu> menus) {
        Store store = candidate.store();
        List<BusinessHourResponse> businessHours = hours.stream()
                .sorted(StoreManagementService.WEEK_ORDER)
                .map(hour -> new BusinessHourResponse(hour.getDay(),
                        BusinessTimes.toTime(hour.getOpenMinute()), BusinessTimes.toTime(hour.getCloseMinute())))
                .toList();
        List<NearbyStoreMenuResponse> menuResponses = menus.stream()
                .map(menu -> new NearbyStoreMenuResponse(menu.getMenuId(), menu.getName(), menu.isOnSale()))
                .toList();
        // 관리자 수정은 가게 전화를 빈 문자열로 지울 수 있다 — "없음" 하나로 보낸다(가게 상세와 같다)
        String phone = store.getPhone() == null || store.getPhone().isBlank() ? null : store.getPhone();
        return new NearbyStoreResponse(store.getStoreId(), store.getName(), store.getAddressRoad(),
                store.getLat(), store.getLng(), candidate.distanceMeters(), phone,
                store.getOpenMode(), store.isManualOpen(), businessHours, menuResponses,
                store.getCompanion(), store.isTakeoutAvailable(), store.isDineInAvailable(), store.getOwnerNote());
    }

    private record Found(Store store, int distanceMeters) {
    }
}
