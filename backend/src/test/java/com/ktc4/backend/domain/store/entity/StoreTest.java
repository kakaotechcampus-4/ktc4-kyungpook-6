package com.ktc4.backend.domain.store.entity;

import com.ktc4.backend.domain.store.enums.StoreStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Store")
class StoreTest {

    private static Store store(String nameNormalized, String addressNormalized, String bizNo) {
        return Store.builder()
                .name("예시분식")
                .nameNormalized(nameNormalized)
                .addressRoad("가상특별시 예시구 샘플로 123")
                .addressNormalized(addressNormalized)
                .bizNo(bizNo)
                .build();
    }

    @Test
    @DisplayName("상태를 넣지 않으면 UNKNOWN 으로 만든다")
    void defaultsStatusToUnknown() {
        assertThat(store("예시분식", "가상특별시예시구샘플로123", null).getStatus()).isEqualTo(StoreStatus.UNKNOWN);
    }

    @Nested
    @DisplayName("updateNormalized")
    class UpdateNormalized {

        @Test
        @DisplayName("값이 달라지면 바꾸고 true 를 돌려준다")
        void updatesAndReturnsTrueWhenChanged() {
            Store store = store("옛날이름", "옛날주소", "123-45-67890");

            boolean changed = store.updateNormalized("예시분식", "가상특별시예시구샘플로123", "1234567890");

            assertThat(changed).isTrue();
            assertThat(store.getNameNormalized()).isEqualTo("예시분식");
            assertThat(store.getAddressNormalized()).isEqualTo("가상특별시예시구샘플로123");
            assertThat(store.getBizNo()).isEqualTo("1234567890");
        }

        @Test
        @DisplayName("이름만 달라져도 true 를 돌려준다")
        void returnsTrueWhenOnlyNameChanged() {
            Store store = store("옛날이름", "가상특별시예시구샘플로123", "1234567890");

            assertThat(store.updateNormalized("예시분식", "가상특별시예시구샘플로123", "1234567890")).isTrue();
        }

        @Test
        @DisplayName("사업자번호만 달라져도 true 를 돌려준다")
        void returnsTrueWhenOnlyBizNoChanged() {
            Store store = store("예시분식", "가상특별시예시구샘플로123", "123-45-67890");

            assertThat(store.updateNormalized("예시분식", "가상특별시예시구샘플로123", "1234567890")).isTrue();
        }

        @Test
        @DisplayName("값이 같으면 false 를 돌려준다")
        void returnsFalseWhenSame() {
            Store store = store("예시분식", "가상특별시예시구샘플로123", "1234567890");

            assertThat(store.updateNormalized("예시분식", "가상특별시예시구샘플로123", "1234567890")).isFalse();
        }

        @Test
        @DisplayName("사업자번호가 둘 다 null 이면 false 를 돌려준다")
        void returnsFalseWhenBothBizNoNull() {
            Store store = store("예시분식", "가상특별시예시구샘플로123", null);

            assertThat(store.updateNormalized("예시분식", "가상특별시예시구샘플로123", null)).isFalse();
        }
    }
}
