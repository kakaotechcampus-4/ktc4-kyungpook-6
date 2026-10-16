package com.ktc4.backend.domain.store.service;

import com.ktc4.backend.domain.member.service.StoreAccessService;
import com.ktc4.backend.domain.store.dto.BusinessHourResponse;
import com.ktc4.backend.domain.store.dto.MenuRequest;
import com.ktc4.backend.domain.store.dto.MenuResponse;
import com.ktc4.backend.domain.store.dto.OperatingInfoRequest;
import com.ktc4.backend.domain.store.dto.StoreDetailResponse;
import com.ktc4.backend.domain.store.entity.Store;
import com.ktc4.backend.domain.store.entity.StoreBusinessHour;
import com.ktc4.backend.domain.store.entity.StoreMenu;
import com.ktc4.backend.domain.store.repository.StoreBusinessHourRepository;
import com.ktc4.backend.domain.store.repository.StoreMenuRepository;
import com.ktc4.backend.domain.store.util.BusinessTimes;
import com.ktc4.backend.global.error.CustomException;
import com.ktc4.backend.global.error.ErrorCode;
import com.ktc4.backend.global.security.AuthMember;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 점주 앱의 가게 관리 — 가게 상세를 보여 주고, 가게 전화·영업시간·메뉴를 통째로 저장한다.
 *
 * <p>관리자와 그 가게에 연결된 점주만 다룰 수 있다({@link StoreAccessService}). 상호명·주소는 여기서 바꾸지 않는다 —
 * 관리자가 사업자등록증을 보고 승인한 값이다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class StoreManagementService {

    // 영업시간은 요일 순(월 → 일), 같은 요일은 여는 시간 순. DB 의 요일 칸은 이름이라 DB 정렬은 알파벳순이 된다.
    private static final Comparator<StoreBusinessHour> WEEK_ORDER = Comparator
            .comparing((StoreBusinessHour hour) -> hour.getDay().ordinal())
            .thenComparingInt(StoreBusinessHour::getOpenMinute);

    private final StoreAccessService storeAccessService;
    private final StoreService storeService;
    private final StoreBusinessHourRepository businessHourRepository;
    private final StoreMenuRepository menuRepository;

    /**
     * 가게 상세를 읽는다 — 상호명·주소·가게 전화·영업시간·메뉴.
     *
     * <p>영업시간은 요일 순(월 → 일), 같은 요일은 여는 시간 순이다. 메뉴는 지운 것을 빼고 등록한 순서다.
     * 사진은 담지 않는다 — 사진은 점주에게 묶여 있어 따로 받는다({@code GET /api/stores/{storeId}/images}).
     *
     * <p>가게·영업시간·메뉴를 쿼리 세 번으로 읽는다. 그 사이 저장이 커밋돼 옛 영업시간과 새 메뉴가 섞이지 않게, 한
     * 시점의 스냅숏으로 읽는다(REPEATABLE READ).
     *
     * @param storeId    가게 ID
     * @param authMember 로그인한 사용자
     * @return 가게 상세
     * @throws CustomException 로그인 정보가 없으면 {@code UNAUTHORIZED},
     *                         관리자도 아니고 이 가게에 연결된 점주도 아니면(없는 가게 포함) {@code FORBIDDEN},
     *                         관리자가 없는 가게를 부르면 {@code STORE_NOT_FOUND}
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public StoreDetailResponse getDetail(Long storeId, AuthMember authMember) {
        storeAccessService.requireAccessTo(storeId, authMember);
        return toDetail(storeService.findStore(storeId));
    }

    /**
     * 가게 전화·영업시간·메뉴를 통째로 바꾼다. 전부 반영되거나 하나도 반영되지 않는다.
     *
     * <p>메뉴는 {@code menuId} 로 맞춘다 — 있으면 그 메뉴를 고치고, 없으면 새로 만들고, 목록에서 빠진 메뉴는 지운 것으로
     * 표시한다. 보낸 {@code menuId} 가 이 가게의 지우지 않은 메뉴가 아니면(남의 가게·없는 것·지운 것) 아무것도 쓰기
     * 전에 거절한다. 이 경우는 대개 화면이 오래된 것(다른 점주가 먼저 저장)이라 앱은 다시 불러오면 된다.
     *
     * <p>가게를 잠그고 쓴다. 같은 가게를 동시에 저장하면 차례로 처리되고 나중 저장이 이긴다.
     * 회원은 잠그지 않는다 — 점주 승인·연결 끊기는 회원 → 가게 순서로 잠그므로, 가게를 잠근 뒤 회원을 잠그면 서로를
     * 기다리며 멈춘다.
     *
     * @param storeId    가게 ID
     * @param request    가게 전화·영업시간·메뉴 (요청 검증을 통과한 값)
     * @param authMember 로그인한 사용자
     * @return 저장한 뒤의 가게 상세
     * @throws CustomException 로그인 정보가 없으면 {@code UNAUTHORIZED},
     *                         관리자도 아니고 이 가게에 연결된 점주도 아니면(없는 가게 포함) {@code FORBIDDEN},
     *                         관리자가 없는 가게를 부르면 {@code STORE_NOT_FOUND},
     *                         {@code menuId} 가 이 가게의 지우지 않은 메뉴가 아니면 {@code INVALID_REQUEST}
     */
    @Transactional
    public StoreDetailResponse save(Long storeId, OperatingInfoRequest request, AuthMember authMember) {
        storeAccessService.requireAccessTo(storeId, authMember);
        Store store = storeService.findStoreForUpdate(storeId);
        // 잠그기 전에 본 연결이 그 사이 끊겼을 수 있다(연결 끊기는 가게를 잠그고 커밋한다) — 잠근 뒤 한 번 더 본다
        storeAccessService.requireAccessTo(storeId, authMember);

        // 잠근 뒤에 읽는다 — 앞선 저장이 지운 메뉴는 여기서 이미 빠져 있다
        Map<Long, StoreMenu> liveMenus = menuRepository.findByStoreStoreIdAndDeletedAtIsNullOrderByMenuIdAsc(storeId)
                .stream()
                .collect(Collectors.toMap(StoreMenu::getMenuId, Function.identity()));
        requireOwnMenus(storeId, request.menus(), liveMenus);

        // 여기부터 쓴다. 위의 검사를 모두 통과했다
        store.changePhone(request.phone().strip());
        replaceBusinessHours(store, request);
        reconcileMenus(store, request.menus(), liveMenus);
        log.info("가게 운영 정보 저장 - storeId={}, memberId={}, 영업시간={}줄, 메뉴={}개",
                storeId, authMember.memberId(), request.businessHours().size(), request.menus().size());
        return toDetail(store);
    }

    private static void requireOwnMenus(Long storeId, List<MenuRequest> menus, Map<Long, StoreMenu> liveMenus) {
        for (MenuRequest menu : menus) {
            if (menu.menuId() != null && !liveMenus.containsKey(menu.menuId())) {
                log.info("가게 운영 정보 저장 거절 - 이 가게의 지우지 않은 메뉴가 아님, storeId={}, menuId={}",
                        storeId, menu.menuId());
                throw new CustomException(ErrorCode.INVALID_REQUEST);
            }
        }
    }

    // 지우고 새로 넣는다. 지우기를 먼저 부른다 — 새 줄은 저장하는 즉시 들어간다(IDENTITY).
    private void replaceBusinessHours(Store store, OperatingInfoRequest request) {
        businessHourRepository.deleteAllByStoreId(store.getStoreId());
        businessHourRepository.saveAll(StoreOperatingInfoService.toBusinessHours(store, request.businessHours()));
    }

    private void reconcileMenus(Store store, List<MenuRequest> menus, Map<Long, StoreMenu> liveMenus) {
        Set<Long> keptIds = new HashSet<>();
        List<StoreMenu> newMenus = new ArrayList<>();
        for (MenuRequest menu : menus) {
            String name = menu.name().strip();
            if (menu.menuId() == null) {
                newMenus.add(StoreMenu.of(store, name, menu.onSale()));
            } else {
                liveMenus.get(menu.menuId()).update(name, menu.onSale());
                keptIds.add(menu.menuId());
            }
        }
        menuRepository.saveAll(newMenus);
        LocalDateTime deletedAt = LocalDateTime.now();
        liveMenus.values().stream()
                .filter(menu -> !keptIds.contains(menu.getMenuId()))
                .forEach(menu -> menu.markDeleted(deletedAt));
    }

    private StoreDetailResponse toDetail(Store store) {
        List<BusinessHourResponse> hours = businessHourRepository.findByStoreStoreId(store.getStoreId()).stream()
                .sorted(WEEK_ORDER)
                .map(hour -> new BusinessHourResponse(hour.getDay(),
                        BusinessTimes.toTime(hour.getOpenMinute()), BusinessTimes.toTime(hour.getCloseMinute())))
                .toList();
        List<MenuResponse> menus = menuRepository.findByStoreStoreIdAndDeletedAtIsNullOrderByMenuIdAsc(store.getStoreId())
                .stream()
                .map(menu -> new MenuResponse(menu.getMenuId(), menu.getName(), menu.isOnSale(), menu.getPhotoFileId()))
                .toList();
        // 관리자 수정은 가게 전화를 빈 문자열로 지울 수 있다 — 화면에는 "없음" 하나로 보낸다
        String phone = store.getPhone() == null || store.getPhone().isBlank() ? null : store.getPhone();
        return new StoreDetailResponse(store.getStoreId(), store.getName(), store.getAddressRoad(), phone, hours, menus);
    }
}
