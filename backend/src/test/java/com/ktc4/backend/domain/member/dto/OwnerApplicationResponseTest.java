package com.ktc4.backend.domain.member.dto;

import com.ktc4.backend.domain.member.entity.Member;
import com.ktc4.backend.domain.member.entity.OwnerInfo;
import com.ktc4.backend.domain.member.entity.SignupBusinessHour;
import com.ktc4.backend.domain.member.entity.SignupMenu;
import com.ktc4.backend.domain.store.dto.BusinessHourResponse;
import com.ktc4.backend.domain.store.enums.BusinessDay;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

// 계정·가게 정보는 모두 가짜 값이다.
@DisplayName("OwnerApplicationResponse")
class OwnerApplicationResponseTest {

    private static OwnerInfo.OwnerInfoBuilder ownerInfo() {
        return OwnerInfo.builder()
                .bizNo("1234567890").storeName("예시분식").representativeName("홍길동").phone("01000000000");
    }

    @Test
    @DisplayName("신청서의 가게 주소·가게 전화·영업시간·메뉴를 적힌 순서대로 내보낸다")
    void mapsStoreInfo() {
        Member member = Member.ownerApplicant("owner@example.com", "hash", ownerInfo()
                .storeAddress("가상특별시 예시구 샘플로 123").storePhone("053-000-0001")
                .businessHours(List.of(
                        new SignupBusinessHour(BusinessDay.SUN, "22:00", "03:00"),
                        new SignupBusinessHour(BusinessDay.MON, "11:00", "24:00")))
                .menus(List.of(new SignupMenu("다슬기국", null), new SignupMenu("가락국수", null)))
                .build());

        OwnerApplicationResponse response = OwnerApplicationResponse.from(member);

        assertThat(response.storeAddress()).isEqualTo("가상특별시 예시구 샘플로 123");
        assertThat(response.storePhone()).isEqualTo("053-000-0001");
        assertThat(response.businessHours()).containsExactly(
                new BusinessHourResponse(BusinessDay.SUN, "22:00", "03:00"),
                new BusinessHourResponse(BusinessDay.MON, "11:00", "24:00"));
        assertThat(response.menus()).containsExactly(new SignupMenuResponse("다슬기국", null), new SignupMenuResponse("가락국수", null));
    }

    @Test
    @DisplayName("가게 정보 칸이 생기기 전에 가입한 신청은 주소·전화가 비어 있고 목록은 빈 목록이다")
    void mapsOldApplication() {
        Member member = Member.ownerApplicant("owner@example.com", "hash", ownerInfo().build());

        OwnerApplicationResponse response = OwnerApplicationResponse.from(member);

        assertThat(response.storeAddress()).isNull();
        assertThat(response.storePhone()).isNull();
        assertThat(response.businessHours()).isEmpty();
        assertThat(response.menus()).isEmpty();
        assertThat(response.openDate()).isNull();
        assertThat(response.corpName()).isNull();
        assertThat(response.businessType()).isNull();
        assertThat(response.businessItem()).isNull();
    }

    @Test
    @DisplayName("신청서의 개업일·법인명·업태·종목을 내보낸다")
    void mapsBizCertFields() {
        Member member = Member.ownerApplicant("owner@example.com", "hash", ownerInfo()
                .openDate(LocalDate.of(2019, 3, 2)).corpName("주식회사 예시")
                .businessType("음식점업").businessItem("한식, 분식").build());

        OwnerApplicationResponse response = OwnerApplicationResponse.from(member);

        assertThat(response.openDate()).isEqualTo(LocalDate.of(2019, 3, 2));
        assertThat(response.corpName()).isEqualTo("주식회사 예시");
        assertThat(response.businessType()).isEqualTo("음식점업");
        assertThat(response.businessItem()).isEqualTo("한식, 분식");
    }
}
