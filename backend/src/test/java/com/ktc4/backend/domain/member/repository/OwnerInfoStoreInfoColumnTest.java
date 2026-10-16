package com.ktc4.backend.domain.member.repository;

import com.ktc4.backend.domain.member.entity.Member;
import com.ktc4.backend.domain.member.entity.OwnerInfo;
import com.ktc4.backend.domain.member.entity.SignupBusinessHour;
import com.ktc4.backend.domain.member.entity.SignupMenu;
import com.ktc4.backend.domain.member.enums.MemberRole;
import com.ktc4.backend.domain.member.enums.MemberStatus;
import com.ktc4.backend.domain.store.enums.BusinessDay;
import com.ktc4.backend.support.PostgresContainerTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 가입 신청서의 가게 정보 칸(가게 주소·가게 전화·영업시간 JSON·메뉴 JSON)이 실제 Postgres 를 거쳐 그대로 읽히는지 본다.
 *
 * <p>JSON 칸은 Hibernate 가 Spring 이 아닌 자기 {@code ObjectMapper} 로 읽는다. 그래서 "모르는 칸이 섞인 기록",
 * "이 칸이 생기기 전의 기록"을 직접 DB 에 넣어 보고 읽힌다는 것을 고정한다. 계정·가게 정보는 모두 가짜 값이다.
 */
@DisplayName("OwnerInfo 가게 정보 칸")
class OwnerInfoStoreInfoColumnTest extends PostgresContainerTest {

    private static final String EMAIL = "store-info@example.com";

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static OwnerInfo.OwnerInfoBuilder ownerInfo() {
        return OwnerInfo.builder()
                .bizNo("1234567890")
                .storeName("예시분식")
                .representativeName("홍길동")
                .phone("01000000000");
    }

    private Long persistOwner(OwnerInfo info) {
        Long memberId = entityManager.persistAndFlush(Member.ownerApplicant(EMAIL, "hash", info)).getMemberId();
        entityManager.clear();
        return memberId;
    }

    @Test
    @DisplayName("영업시간 21줄·메뉴 50개(사진 ID 포함)가 순서·값 그대로 왕복한다 — 요소가 레코드로 읽히는지까지")
    void roundTripsFullLists() {
        List<SignupBusinessHour> hours = new ArrayList<>();
        for (BusinessDay day : BusinessDay.values()) {
            hours.add(new SignupBusinessHour(day, "00:00", "06:00"));
            hours.add(new SignupBusinessHour(day, "10:00", "14:00"));
            hours.add(new SignupBusinessHour(day, "21:00", "24:00"));
        }
        List<SignupMenu> menus = IntStream.rangeClosed(1, 50).mapToObj(i -> new SignupMenu("메뉴" + (51 - i),
                        i % 2 == 0 ? null : String.format("00000000-0000-4000-8000-%012d", i))).toList();

        Long memberId = persistOwner(ownerInfo()
                .storeAddress("가상특별시 예시구 샘플로 1").storePhone("053-000-0001")
                .businessHours(hours).menus(menus).build());

        OwnerInfo read = memberRepository.findById(memberId).orElseThrow().getOwnerInfo();
        assertThat(read.getStoreAddress()).isEqualTo("가상특별시 예시구 샘플로 1");
        assertThat(read.getStorePhone()).isEqualTo("053-000-0001");
        // 레코드끼리 비교한다 — 제네릭이 지워져 LinkedHashMap 으로 읽혔다면 여기서 다르다
        assertThat(read.getBusinessHours()).hasSize(21).containsExactlyElementsOf(hours);
        assertThat(read.getBusinessHours().get(2).close()).isEqualTo("24:00");
        assertThat(read.getMenus()).hasSize(50).containsExactlyElementsOf(menus);
    }

    static Stream<Arguments> storedRows() {
        return Stream.of(
                Arguments.of("이 칸이 생기기 전 기록(SQL NULL)", null, null, List.of(), List.of()),
                Arguments.of("JSON null", "null", "null", List.of(), List.of()),
                Arguments.of("빈 배열", "[]", "[]", List.of(), List.of()),
                Arguments.of("영업시간 줄에 모르는 칸",
                        "[{\"day\":\"MON\",\"open\":\"11:00\",\"close\":\"21:00\",\"note\":\"브레이크\"}]", "[]",
                        List.of(new SignupBusinessHour(BusinessDay.MON, "11:00", "21:00")), List.of()),
                Arguments.of("메뉴에 모르는 칸", "[]",
                        "[{\"name\":\"김치찌개\",\"price\":8000},{\"name\":\"라면\"}]",
                        List.of(), List.of(new SignupMenu("김치찌개", null), new SignupMenu("라면", null))),
                Arguments.of("메뉴 사진 칸", "[]",
                        "[{\"name\":\"김치찌개\",\"photoFileId\":\"9c8b7a6d-5e4f-4a3b-8c2d-1e0f9a8b7c6d\"}]",
                        List.of(), List.of(new SignupMenu("김치찌개", "9c8b7a6d-5e4f-4a3b-8c2d-1e0f9a8b7c6d"))));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("storedRows")
    @DisplayName("DB 에 있는 여러 모양의 기록이 로그인 조회·신청 목록 조회에서 깨지지 않고 읽힌다")
    void readsStoredShapes(String name, String hoursJson, String menusJson,
                           List<SignupBusinessHour> expectedHours, List<SignupMenu> expectedMenus) {
        Long memberId = persistOwner(ownerInfo().build());
        jdbcTemplate.update("update member set store_address = null, store_phone = null,"
                        + " store_business_hours = cast(? as jsonb), store_menus = cast(? as jsonb) where member_id = ?",
                hoursJson, menusJson, memberId);
        entityManager.clear();

        // 로그인은 이메일로 회원을 읽는다
        OwnerInfo byEmail = memberRepository.findByEmail(EMAIL).orElseThrow().getOwnerInfo();
        assertThat(byEmail.getBusinessHours()).containsExactlyElementsOf(expectedHours);
        assertThat(byEmail.getMenus()).containsExactlyElementsOf(expectedMenus);
        assertThat(byEmail.getStoreAddress()).isNull();
        assertThat(byEmail.getStorePhone()).isNull();
        entityManager.clear();

        // 관리자 신청 목록은 페이지로 읽는다
        assertThat(memberRepository.findByRoleAndStatus(MemberRole.OWNER, MemberStatus.PENDING, PageRequest.of(0, 20))
                .getContent())
                .extracting(Member::getMemberId).contains(memberId);
    }

    @Test
    @DisplayName("승인해 회원 행을 다시 써도 신청서의 JSON 은 다시 쓰이지 않아, 옛 코드가 모르는 칸이 지워지지 않는다")
    void approvalDoesNotRewriteApplicationJson() {
        Long memberId = persistOwner(ownerInfo().build());
        jdbcTemplate.update("update member set store_menus = cast(? as jsonb) where member_id = ?",
                "[{\"name\":\"김치찌개\",\"price\":8000}]", memberId);
        entityManager.clear();

        Member member = memberRepository.findById(memberId).orElseThrow();
        member.approve(LocalDateTime.of(2026, 10, 13, 10, 0));
        entityManager.flush();
        entityManager.clear();

        String stored = jdbcTemplate.queryForObject(
                "select store_menus::text from member where member_id = ?", String.class, memberId);
        assertThat(stored).contains("\"price\"");
        assertThat(memberRepository.findById(memberId).orElseThrow().getStatus()).isEqualTo(MemberStatus.APPROVED);
    }
}
