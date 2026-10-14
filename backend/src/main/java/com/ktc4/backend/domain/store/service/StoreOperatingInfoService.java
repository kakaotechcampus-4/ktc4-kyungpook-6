package com.ktc4.backend.domain.store.service;

import com.ktc4.backend.domain.store.dto.BusinessHourRequest;
import com.ktc4.backend.domain.store.entity.Store;
import com.ktc4.backend.domain.store.entity.StoreBusinessHour;
import com.ktc4.backend.domain.store.entity.StoreMenu;
import com.ktc4.backend.domain.store.repository.StoreBusinessHourRepository;
import com.ktc4.backend.domain.store.repository.StoreMenuRepository;
import com.ktc4.backend.domain.store.util.BusinessTimes;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 가게의 운영 정보(가게 전화·영업시간·메뉴)를 다룬다.
 *
 * <p>지금은 점주 가입 신청에 적힌 값을 가게에 옮겨 적는 일만 한다. 점주가 가게 관리에서 고치는 저장은 다음 단계다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class StoreOperatingInfoService {

    // 항목마다 채운 결과 — 로그에 남긴다
    private static final String FILLED = "채움";
    private static final String KEPT_EXISTING = "있어서 그대로";
    private static final String NOTHING_TO_FILL = "신청서에 없음";

    private final StoreBusinessHourRepository businessHourRepository;
    private final StoreMenuRepository menuRepository;

    /**
     * 가게의 빈 운영 정보만 채운다. 가게 전화·영업시간·메뉴를 항목마다 따로 본다 — 이미 값이 있는 항목은 건드리지 않는다.
     *
     * <p>점주 승인은 "이 사람이 이 가게 점주다"를 인정하는 일이지 가게 정보를 바꾸는 일이 아니다. 그래서 먼저 연결된
     * 점주가 정리해 둔 값을 나중 점주의 신청서가 덮어쓰지 않게 한다. 틀린 값은 점주가 가게 관리에서 고친다.
     *
     * <p>호출자는 {@link StoreService#findStoreForUpdate} 로 잠근 가게를 넘겨야 한다. 잠그지 않으면 두 승인이 동시에
     * "비어 있다"고 보고 두 번 넣는다.
     *
     * @param store       잠긴 가게
     * @param phone       가게 전화번호. 비어 있으면 채우지 않는다
     * @param hours       영업시간. 비어 있으면 채우지 않는다. 형식은 요청 검증을 통과한 값이어야 한다
     * @param menuNames   메뉴 이름. 비어 있으면 채우지 않는다. 처음에는 모두 판매 중이다
     * @throws IllegalStateException 영업시간 형식이 틀리면 — 입구에서 검증한 값만 오므로 생기면 버그다.
     *                               조용히 건너뛰지 않고 호출한 트랜잭션(승인)을 통째로 되돌린다
     */
    @Transactional
    public void fillIfEmpty(Store store, String phone, List<BusinessHourRequest> hours, List<String> menuNames) {
        // 항목마다 "채울지"를 따로 정한다. 관리자 승인 화면이 생겨 관리자가 항목별로 고르게 되면,
        // 아래 세 메서드의 "비어 있을 때만" 조건을 관리자가 고른 값으로 바꾸는 자리다.
        String phoneResult = fillPhone(store, phone);
        String hoursResult = fillHours(store, hours);
        String menusResult = fillMenus(store, menuNames);
        // 옮겨 적지 않은 항목도 남긴다 — "승인했는데 가게에 영업시간이 없다"는 문의가 오면 이 줄로 이유를 찾는다
        log.info("가게 빈 칸 채우기 - storeId={}, 가게 전화={}, 영업시간={}, 메뉴={}",
                store.getStoreId(), phoneResult, hoursResult, menusResult);
    }

    private static String fillPhone(Store store, String phone) {
        if (phone == null || phone.isBlank()) {
            return NOTHING_TO_FILL;
        }
        boolean wasEmpty = store.getPhone() == null || store.getPhone().isBlank();
        store.recordPhoneIfAbsent(phone);
        return wasEmpty ? FILLED : KEPT_EXISTING;
    }

    private String fillHours(Store store, List<BusinessHourRequest> hours) {
        if (hours.isEmpty()) {
            return NOTHING_TO_FILL;
        }
        if (businessHourRepository.existsByStoreStoreId(store.getStoreId())) {
            return KEPT_EXISTING;
        }
        businessHourRepository.saveAll(toBusinessHours(store, hours));
        return FILLED + "(" + hours.size() + "줄)";
    }

    private String fillMenus(Store store, List<String> menuNames) {
        if (menuNames.isEmpty()) {
            return NOTHING_TO_FILL;
        }
        if (menuRepository.existsByStoreStoreId(store.getStoreId())) {
            return KEPT_EXISTING;
        }
        menuRepository.saveAll(menuNames.stream().map(name -> StoreMenu.onSale(store, name)).toList());
        return FILLED + "(" + menuNames.size() + "개)";
    }

    private static List<StoreBusinessHour> toBusinessHours(Store store, List<BusinessHourRequest> hours) {
        return hours.stream()
                .map(hour -> StoreBusinessHour.of(store, hour.day(),
                        BusinessTimes.toOpenMinute(hour.open()).orElseThrow(() -> invalidTime(store, hour)),
                        BusinessTimes.toCloseMinute(hour.close()).orElseThrow(() -> invalidTime(store, hour))))
                .toList();
    }

    private static IllegalStateException invalidTime(Store store, BusinessHourRequest hour) {
        return new IllegalStateException("영업시간 형식이 올바르지 않습니다 - storeId=" + store.getStoreId()
                + ", day=" + hour.day() + ", open=" + hour.open() + ", close=" + hour.close());
    }
}
