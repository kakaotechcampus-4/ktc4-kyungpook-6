package com.ktc4.backend.domain.store.dto;

import com.ktc4.backend.domain.store.entity.Store;
import com.ktc4.backend.domain.store.entity.StoreMenu;
import com.ktc4.backend.domain.store.enums.BusinessDay;
import com.ktc4.backend.domain.store.enums.StoreChangeField;
import com.ktc4.backend.domain.store.enums.StoreStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 변경 기록에 남는 값의 모양 — DB 를 읽는 사람에게는 이 모양이 유일한 명세라 문자열 그대로 고정한다.
 * 없는 값이 무엇이 되는지도 본다. 순서만 다른 저장이 기록되지 않는지는 서비스 테스트가 본다. 가게 정보는 모두 가짜 값이다.
 */
@DisplayName("StoreSnapshot")
class StoreSnapshotTest {

    private static Store store(String phone) {
        return Store.builder()
                .name("예시분식 \"본점\"").nameNormalized("예시분식본점")
                .addressRoad("가상특별시 예시구 샘플로 123").addressNormalized("가상특별시예시구샘플로123")
                .status(StoreStatus.SUSPENDED).phone(phone)
                .build();
    }

    private static StoreMenu menu(long menuId, String name, boolean onSale) {
        StoreMenu menu = StoreMenu.of(null, name, onSale);
        ReflectionTestUtils.setField(menu, "menuId", menuId);
        return menu;
    }

    @Test
    @DisplayName("항목마다 가게 상세 응답과 같은 모양의 JSON — 영업시간은 요일 순·같은 요일은 여는 시간 순, 메뉴는 menuId 순")
    void serializesEachFieldInDetailShape() {
        // 일부러 섞어 넣는다 — 알파벳순이면 FRI < MON < SUN, 넣은 순서 그대로면 SUN 이 앞에 온다
        List<BusinessHourMinutes> hours = List.of(
                new BusinessHourMinutes(BusinessDay.SUN, 600, 840),
                new BusinessHourMinutes(BusinessDay.MON, 1020, 1440),
                new BusinessHourMinutes(BusinessDay.FRI, 1260, 120),
                new BusinessHourMinutes(BusinessDay.MON, 660, 900));
        List<StoreMenu> menus = List.of(menu(9L, "라면", false), menu(3L, "김밥", true));

        StoreSnapshot snapshot = StoreSnapshot.of(store("053-123-4567"), hours, menus);

        assertThat(snapshot.valueOf(StoreChangeField.NAME)).isEqualTo("\"예시분식 \\\"본점\\\"\"");
        assertThat(snapshot.valueOf(StoreChangeField.ADDRESS_ROAD)).isEqualTo("\"가상특별시 예시구 샘플로 123\"");
        assertThat(snapshot.valueOf(StoreChangeField.PHONE)).isEqualTo("\"053-123-4567\"");
        assertThat(snapshot.valueOf(StoreChangeField.STATUS)).isEqualTo("\"SUSPENDED\"");
        assertThat(snapshot.valueOf(StoreChangeField.BUSINESS_HOURS)).isEqualTo("["
                + "{\"day\":\"MON\",\"open\":\"11:00\",\"close\":\"15:00\"},"
                + "{\"day\":\"MON\",\"open\":\"17:00\",\"close\":\"24:00\"},"
                + "{\"day\":\"FRI\",\"open\":\"21:00\",\"close\":\"02:00\"},"
                + "{\"day\":\"SUN\",\"open\":\"10:00\",\"close\":\"14:00\"}]");
        assertThat(snapshot.valueOf(StoreChangeField.MENUS)).isEqualTo("["
                + "{\"menuId\":3,\"name\":\"김밥\",\"onSale\":true},"
                + "{\"menuId\":9,\"name\":\"라면\",\"onSale\":false}]");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "  "})
    @DisplayName("전화가 없거나 비었거나 공백뿐이면 모두 \"없음\"(null) — 관리자 수정의 빈 문자열은 지움이다")
    void blankPhoneIsAbsent(String phone) {
        assertThat(StoreSnapshot.of(store(phone), List.of(), List.of()).valueOf(StoreChangeField.PHONE)).isNull();
    }

    @Test
    @DisplayName("영업시간·메뉴가 없으면 빈 목록 \"[]\" — 없음(null)과 구분한다")
    void emptyListsAreEmptyArrays() {
        StoreSnapshot snapshot = StoreSnapshot.of(store(null), List.of(), List.of());

        assertThat(snapshot.valueOf(StoreChangeField.BUSINESS_HOURS)).isEqualTo("[]");
        assertThat(snapshot.valueOf(StoreChangeField.MENUS)).isEqualTo("[]");
    }
}
