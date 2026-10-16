package com.ktc4.backend.domain.store.dto;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ktc4.backend.domain.store.entity.Store;
import com.ktc4.backend.domain.store.entity.StoreMenu;
import com.ktc4.backend.domain.store.enums.StoreChangeField;
import com.ktc4.backend.domain.store.util.BusinessTimes;

import java.util.Comparator;
import java.util.List;

/**
 * 한 순간의 가게 정보 — 변경 기록 항목마다 JSON 문자열 하나. 바꾸기 전과 바꾼 뒤를 하나씩 만들어 비교한다.
 *
 * <p>만드는 순간 문자열로 굳힌다. 엔티티를 들고 있으면, 저장이 그 엔티티를 제자리에서 고친 뒤에 꺼낼 때 이미 바뀐 값이
 * 나와 "전"과 "후"가 같아진다.
 *
 * <p>같은 정보는 같은 문자열이 되게 순서를 고정한다 — 영업시간은 요일 순(월 → 일)·같은 요일은 여는 시간 순, 메뉴는
 * {@code menuId} 순. 모양은 가게 상세 응답과 같지만 응답 DTO 를 쓰지 않고 아래의 기록 전용 record 로 고정한다 — 응답에 칸이
 * 늘어도 쌓인 기록의 모양이 바뀌지 않게. 없는 값은 {@code null} 이다(전화가 비었거나 공백뿐인 경우 포함 —
 * 관리자 수정은 빈 문자열로 전화를 지운다).
 *
 * @param name          가게명 (JSON 문자열)
 * @param addressRoad   도로명 주소 (JSON 문자열)
 * @param phone         가게 전화 (JSON 문자열). 없으면 {@code null}
 * @param status        영업 상태 (JSON 문자열)
 * @param businessHours 영업시간 {@code [{"day","open","close"}]}
 * @param menus         지우지 않은 메뉴 {@code [{"menuId","name","onSale"}]}
 */
public record StoreSnapshot(String name, String addressRoad, String phone, String status,
                            String businessHours, String menus) {

    // 기록 값은 이 모양 그대로 DB 에 남는다 — 스프링 설정이 바뀌어도 모양이 흔들리지 않게 기본 설정을 따로 쓴다
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final Comparator<BusinessHourMinutes> WEEK_ORDER = Comparator
            .comparing((BusinessHourMinutes hour) -> hour.day().ordinal())
            .thenComparingInt(BusinessHourMinutes::openMinute);

    /**
     * 가게와 그 영업시간·메뉴로 스냅숏을 만든다.
     *
     * @param store         가게
     * @param businessHours 그 가게의 영업시간 (순서 상관없음)
     * @param liveMenus     그 가게의 지우지 않은 메뉴 (순서 상관없음)
     * @return 스냅숏
     */
    public static StoreSnapshot of(Store store, List<BusinessHourMinutes> businessHours, List<StoreMenu> liveMenus) {
        String phone = store.getPhone() == null || store.getPhone().isBlank() ? null : toJson(store.getPhone());
        List<HourValue> hours = businessHours.stream()
                .sorted(WEEK_ORDER)
                .map(hour -> new HourValue(hour.day().name(),
                        BusinessTimes.toTime(hour.openMinute()), BusinessTimes.toTime(hour.closeMinute())))
                .toList();
        List<MenuValue> menus = liveMenus.stream()
                .sorted(Comparator.comparing(StoreMenu::getMenuId))
                .map(menu -> new MenuValue(menu.getMenuId(), menu.getName(), menu.isOnSale()))
                .toList();
        return new StoreSnapshot(toJson(store.getName()), toJson(store.getAddressRoad()), phone,
                toJson(store.getStatus().name()), toJson(hours), toJson(menus));
    }

    /**
     * 항목 하나의 값.
     *
     * @param field 항목
     * @return JSON 문자열. 없으면 {@code null}
     */
    public String valueOf(StoreChangeField field) {
        return switch (field) {
            case NAME -> name;
            case ADDRESS_ROAD -> addressRoad;
            case PHONE -> phone;
            case STATUS -> status;
            case BUSINESS_HOURS -> businessHours;
            case MENUS -> menus;
        };
    }

    // 기록에 남는 영업시간 한 줄·메뉴 한 개의 모양. 칸 이름·순서가 곧 DB 에 쌓이는 JSON 이다
    private record HourValue(String day, String open, String close) {
    }

    private record MenuValue(Long menuId, String name, boolean onSale) {
    }

    private static String toJson(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            // 문자열·숫자·불린·enum 으로만 된 record 라 실패할 일이 없다 — 생기면 버그다
            throw new IllegalStateException("가게 스냅숏을 JSON 으로 바꾸지 못했습니다", e);
        }
    }
}
