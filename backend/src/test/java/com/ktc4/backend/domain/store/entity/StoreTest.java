package com.ktc4.backend.domain.store.entity;

import com.ktc4.backend.domain.store.enums.StoreStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link Store} 의 변경 메서드(수정 API 구현 티켓, PROMPT-76) 단위 테스트.
 *
 * <p>순수 자바 단위 테스트다 — DB 왕복은 {@code StoreServiceTest}(서비스가 저장까지 이어지는지)와
 * 통합 테스트가 맡고, 여기서는 "null 인 필드는 안 바뀐다"는 부분 수정 규칙만 확인한다.
 */
class StoreTest {

    private static Store store() {
        return Store.builder()
                .name("맛나 치킨")
                .nameNormalized("맛나치킨")
                .addressRoad("대구광역시 북구 대학로 80")
                .addressNormalized("대구광역시북구대학로80")
                .status(StoreStatus.OPEN)
                .phone("010-1234-5678")
                .lat(35.123)
                .lng(128.456)
                .category("치킨")
                .bizNo("1234567890")
                .build();
    }

    @Nested
    @DisplayName("updateBasicInfo — null 인 필드는 바뀌지 않는다")
    class UpdateBasicInfo {

        @Test
        @DisplayName("모든 필드를 채우면 전부 바뀐다")
        void updatesAllFieldsWhenAllProvided() {
            Store store = store();

            store.updateBasicInfo("새이름", "새이름", "새주소", "새주소정규화",
                    "010-9999-0000", StoreStatus.CLOSED);

            assertThat(store.getName()).isEqualTo("새이름");
            assertThat(store.getNameNormalized()).isEqualTo("새이름");
            assertThat(store.getAddressRoad()).isEqualTo("새주소");
            assertThat(store.getAddressNormalized()).isEqualTo("새주소정규화");
            assertThat(store.getPhone()).isEqualTo("010-9999-0000");
            assertThat(store.getStatus()).isEqualTo(StoreStatus.CLOSED);
        }

        @Test
        @DisplayName("name 이 null 이면 name·nameNormalized 둘 다 그대로다")
        void keepsNameWhenNull() {
            Store store = store();

            store.updateBasicInfo(null, null, "새주소", "새주소정규화", "010-9999-0000", StoreStatus.CLOSED);

            assertThat(store.getName()).isEqualTo("맛나 치킨");
            assertThat(store.getNameNormalized()).isEqualTo("맛나치킨");
        }

        @Test
        @DisplayName("addressRoad 가 null 이면 addressRoad·addressNormalized 둘 다 그대로다")
        void keepsAddressWhenNull() {
            Store store = store();

            store.updateBasicInfo("새이름", "새이름", null, null, "010-9999-0000", StoreStatus.CLOSED);

            assertThat(store.getAddressRoad()).isEqualTo("대구광역시 북구 대학로 80");
            assertThat(store.getAddressNormalized()).isEqualTo("대구광역시북구대학로80");
        }

        @Test
        @DisplayName("status 가 null 이면 그대로다")
        void keepsStatusWhenNull() {
            Store store = store();

            store.updateBasicInfo("새이름", "새이름", "새주소", "새주소정규화", "010-9999-0000", null);

            assertThat(store.getStatus()).isEqualTo(StoreStatus.OPEN);
        }

        @Test
        @DisplayName("phone 이 null 이면 그대로고, 빈 문자열이면 지워진다 — null 과 빈 값은 다르다")
        void distinguishesNullFromEmptyForPhone() {
            Store keepsPhone = store();
            keepsPhone.updateBasicInfo(null, null, null, null, null, null);
            assertThat(keepsPhone.getPhone()).isEqualTo("010-1234-5678");

            Store clearsPhone = store();
            clearsPhone.updateBasicInfo(null, null, null, null, "", null);
            assertThat(clearsPhone.getPhone()).isEmpty();
        }

        @Test
        @DisplayName("이 메서드가 다루지 않는 필드(category/lat/lng/bizNo)는 안 건드린다")
        void doesNotTouchUnrelatedFields() {
            Store store = store();

            store.updateBasicInfo("새이름", "새이름", "새주소", "새주소정규화", "010-9999-0000", StoreStatus.CLOSED);

            assertThat(store.getCategory()).isEqualTo("치킨");
            assertThat(store.getLat()).isEqualTo(35.123);
            assertThat(store.getLng()).isEqualTo(128.456);
            assertThat(store.getBizNo()).isEqualTo("1234567890");
        }
    }

    @Nested
    @DisplayName("confirm — 확인 시각을 그대로 기록한다")
    class Confirm {

        @Test
        @DisplayName("넘긴 시각으로 lastCheckedAt 이 바뀐다")
        void setsLastCheckedAt() {
            Store store = store();
            LocalDateTime confirmedAt = LocalDateTime.of(2026, 9, 23, 10, 0);

            store.confirm(confirmedAt);

            assertThat(store.getLastCheckedAt()).isEqualTo(confirmedAt);
        }

        @Test
        @DisplayName("다른 필드는 안 건드린다")
        void doesNotTouchOtherFields() {
            Store store = store();

            store.confirm(LocalDateTime.now());

            assertThat(store.getName()).isEqualTo("맛나 치킨");
            assertThat(store.getStatus()).isEqualTo(StoreStatus.OPEN);
            assertThat(store.getCategory()).isEqualTo("치킨");
            assertThat(store.getLat()).isEqualTo(35.123);
            assertThat(store.getLng()).isEqualTo(128.456);
            assertThat(store.getBizNo()).isEqualTo("1234567890");
        }
    }
}
