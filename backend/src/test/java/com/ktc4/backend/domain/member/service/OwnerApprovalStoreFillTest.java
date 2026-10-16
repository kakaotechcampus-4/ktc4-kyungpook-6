package com.ktc4.backend.domain.member.service;

import com.ktc4.backend.domain.member.entity.Member;
import com.ktc4.backend.domain.member.entity.OwnerInfo;
import com.ktc4.backend.domain.member.entity.SignupBusinessHour;
import com.ktc4.backend.domain.member.entity.SignupMenu;
import com.ktc4.backend.domain.store.entity.Store;
import com.ktc4.backend.domain.store.entity.StoreBusinessHour;
import com.ktc4.backend.domain.store.entity.StoreMenu;
import com.ktc4.backend.domain.store.enums.BusinessDay;
import com.ktc4.backend.domain.store.enums.StoreChangeField;
import com.ktc4.backend.domain.store.enums.StoreChangeSource;
import com.ktc4.backend.domain.store.enums.StoreStatus;
import com.ktc4.backend.domain.store.repository.StoreBusinessHourRepository;
import com.ktc4.backend.domain.store.repository.StoreMenuRepository;
import com.ktc4.backend.domain.store.service.StoreChangeHistoryService;
import com.ktc4.backend.domain.store.service.StoreOperatingInfoService;
import com.ktc4.backend.domain.store.service.StoreService;
import com.ktc4.backend.support.PostgresContainerTest;
import com.ktc4.backend.support.StoreChangeHistoryRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;

import static com.ktc4.backend.support.StoreChangeHistoryRow.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * 점주를 가게에 붙일 때 신청서의 가게 전화·영업시간·메뉴가 가게의 빈 항목에만 옮겨 적히는지 실제 DB 로 본다.
 *
 * <p>규칙: 이 점주에게 살아 있는 다른 가게가 없을 때만, 항목마다 비어 있으면 채운다. 계정·가게 정보는 모두 가짜 값이다.
 */
@Import({OwnerApprovalService.class, StoreService.class, StoreChangeHistoryService.class, StoreOperatingInfoService.class})
@DisplayName("점주 승인·연결 때 가게 빈 칸 채우기")
class OwnerApprovalStoreFillTest extends PostgresContainerTest {

    private static final List<SignupBusinessHour> APPLIED_HOURS = List.of(
            new SignupBusinessHour(BusinessDay.MON, "11:00", "15:00"),
            new SignupBusinessHour(BusinessDay.MON, "17:00", "24:00"),
            new SignupBusinessHour(BusinessDay.FRI, "21:30", "02:00"));
    private static final List<SignupMenu> APPLIED_MENUS = List.of(
            new SignupMenu("나물비빔밥"), new SignupMenu("가락국수"), new SignupMenu("다슬기국"));
    private static final String APPLIED_STORE_PHONE = "053-000-0001";

    @Autowired
    private OwnerApprovalService ownerApprovalService;

    @Autowired
    private StoreBusinessHourRepository businessHourRepository;

    @Autowired
    private StoreMenuRepository menuRepository;

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long adminId;
    private int emailSequence;

    @BeforeEach
    void setUp() {
        adminId = entityManager.persistAndFlush(Member.admin("admin@example.com", "hash")).getMemberId();
    }

    private Store persistStore(String phone) {
        return entityManager.persistAndFlush(Store.builder()
                .name("예시분식")
                .nameNormalized("예시분식")
                .addressRoad("가상특별시 예시구 샘플로 123")
                .addressNormalized("가상특별시예시구샘플로123")
                .status(StoreStatus.OPEN)
                .phone(phone)
                .build());
    }

    private Long persistApplicant(String storePhone, List<SignupBusinessHour> hours, List<SignupMenu> menus) {
        OwnerInfo info = OwnerInfo.builder()
                .bizNo("1234567890").storeName("예시분식").representativeName("홍길동").phone("01000000000")
                .storeAddress("가상특별시 예시구 샘플로 123").storePhone(storePhone)
                .businessHours(hours).menus(menus)
                .build();
        String email = "owner" + (++emailSequence) + "@example.com";
        return entityManager.persistAndFlush(Member.ownerApplicant(email, "hash", info)).getMemberId();
    }

    private Long persistApplicant() {
        return persistApplicant(APPLIED_STORE_PHONE, APPLIED_HOURS, APPLIED_MENUS);
    }

    private void approve(Long memberId, Store store) {
        ownerApprovalService.approve(memberId, store.getStoreId(), adminId);
        entityManager.flush();
        entityManager.clear();
    }

    private void link(Long memberId, Store store) {
        ownerApprovalService.linkStore(memberId, store.getStoreId(), adminId);
        entityManager.flush();
        entityManager.clear();
    }

    private void unlink(Long memberId, Store store) {
        ownerApprovalService.unlinkStore(memberId, store.getStoreId(), adminId);
        entityManager.flush();
        entityManager.clear();
    }

    private String phoneOf(Store store) {
        return entityManager.find(Store.class, store.getStoreId()).getPhone();
    }

    private List<StoreBusinessHour> hoursOf(Store store) {
        return businessHourRepository.findAll().stream()
                .filter(hour -> hour.getStore().getStoreId().equals(store.getStoreId()))
                .sorted(Comparator.comparing(StoreBusinessHour::getBusinessHourId))
                .toList();
    }

    private List<StoreMenu> menusOf(Store store) {
        return menuRepository.findAll().stream()
                .filter(menu -> menu.getStore().getStoreId().equals(store.getStoreId()))
                .sorted(Comparator.comparing(StoreMenu::getMenuId))
                .toList();
    }

    private void assertFilledFromApplication(Store store) {
        assertThat(phoneOf(store)).isEqualTo(APPLIED_STORE_PHONE);
        assertThat(hoursOf(store))
                .extracting(StoreBusinessHour::getDay, StoreBusinessHour::getOpenMinute, StoreBusinessHour::getCloseMinute)
                .containsExactly(
                        tuple(BusinessDay.MON, 660, 900),
                        tuple(BusinessDay.MON, 1020, 1440),
                        tuple(BusinessDay.FRI, 1290, 120));
        assertThat(menusOf(store)).extracting(StoreMenu::getName).containsExactly("나물비빔밥", "가락국수", "다슬기국");
        assertThat(menusOf(store)).allMatch(StoreMenu::isOnSale);
    }

    private void assertNothingFilled(Store store) {
        assertThat(phoneOf(store)).isNull();
        assertThat(hoursOf(store)).isEmpty();
        assertThat(menusOf(store)).isEmpty();
    }

    @Nested
    @DisplayName("승인")
    class Approve {

        @Test
        @DisplayName("빈 가게면 가게 전화·영업시간(분 단위)·메뉴(판매 중, 신청 순서)를 옮겨 적는다")
        void fillsEmptyStore() {
            Store store = persistStore(null);

            approve(persistApplicant(), store);

            assertFilledFromApplication(store);
        }

        @Test
        @DisplayName("가게 전화만 있으면 전화는 그대로 두고 영업시간·메뉴만 옮겨 적는다")
        void keepsExistingPhone() {
            Store store = persistStore("053-999-9999");

            approve(persistApplicant(), store);

            assertThat(phoneOf(store)).isEqualTo("053-999-9999");
            assertThat(hoursOf(store)).hasSize(3);
            assertThat(menusOf(store)).hasSize(3);
        }

        @Test
        @DisplayName("영업시간만 있으면 영업시간은 그대로 두고 전화·메뉴만 옮겨 적는다")
        void keepsExistingHours() {
            Store store = persistStore(null);
            entityManager.persistAndFlush(StoreBusinessHour.of(store, BusinessDay.SUN, 600, 1200));

            approve(persistApplicant(), store);

            assertThat(hoursOf(store)).extracting(StoreBusinessHour::getDay).containsExactly(BusinessDay.SUN);
            assertThat(phoneOf(store)).isEqualTo(APPLIED_STORE_PHONE);
            assertThat(menusOf(store)).hasSize(3);
        }

        @Test
        @DisplayName("메뉴만 있으면 메뉴는 그대로 두고 전화·영업시간만 옮겨 적는다")
        void keepsExistingMenus() {
            Store store = persistStore(null);
            entityManager.persistAndFlush(StoreMenu.onSale(store, "김치찌개"));

            approve(persistApplicant(), store);

            assertThat(menusOf(store)).extracting(StoreMenu::getName).containsExactly("김치찌개");
            assertThat(phoneOf(store)).isEqualTo(APPLIED_STORE_PHONE);
            assertThat(hoursOf(store)).hasSize(3);
        }

        @Test
        @DisplayName("지우지 않은 메뉴가 하나라도 있으면 메뉴는 그대로 둔다 — 지운 메뉴가 섞여 있어도")
        void liveMenuAmongDeletedKeepsMenus() {
            Store store = persistStore(null);
            entityManager.persistAndFlush(StoreMenu.onSale(store, "김치찌개"));
            StoreMenu deleted = StoreMenu.onSale(store, "옛 메뉴");
            deleted.markDeleted(LocalDateTime.of(2026, 1, 1, 9, 0));
            entityManager.persistAndFlush(deleted);

            approve(persistApplicant(), store);

            assertThat(menusOf(store)).extracting(StoreMenu::getName).containsExactly("김치찌개", "옛 메뉴");
        }

        @Test
        @DisplayName("지운 메뉴만 있는 가게는 메뉴가 없는 가게로 보고 옮겨 적는다")
        void deletedOnlyMenusCountAsEmpty() {
            Store store = persistStore(null);
            StoreMenu deleted = StoreMenu.onSale(store, "옛 메뉴");
            deleted.markDeleted(LocalDateTime.of(2026, 1, 1, 9, 0));
            entityManager.persistAndFlush(deleted);

            approve(persistApplicant(), store);

            assertThat(menusOf(store)).filteredOn(menu -> menu.getDeletedAt() == null)
                    .extracting(StoreMenu::getName).containsExactly("나물비빔밥", "가락국수", "다슬기국");
        }

        @ParameterizedTest(name = "가게 전화 \"{0}\"")
        @ValueSource(strings = {"", "   "})
        @DisplayName("가게 전화가 빈 문자열·공백뿐이면 비어 있는 것으로 보고 채운다")
        void fillsBlankPhone(String blank) {
            Store store = persistStore(blank);

            approve(persistApplicant(), store);

            assertThat(phoneOf(store)).isEqualTo(APPLIED_STORE_PHONE);
        }

        @Test
        @DisplayName("가게 정보 칸이 생기기 전에 가입한 신청자는 옮겨 적을 것이 없다")
        void oldApplicantFillsNothing() {
            Store store = persistStore(null);

            approve(persistApplicant(null, null, null), store);

            assertNothingFilled(store);
        }
    }

    @Nested
    @DisplayName("따로 연결 — 이 점주에게 살아 있는 다른 가게가 없을 때만 채운다")
    class Link {

        @Test
        @DisplayName("이미 가게가 있는 점주를 두 번째 가게에 이으면 옮겨 적지 않는다")
        void secondStoreIsNotFilled() {
            Store first = persistStore(null);
            Store second = persistStore(null);
            Long memberId = persistApplicant();
            approve(memberId, first);

            link(memberId, second);

            assertNothingFilled(second);
        }

        @Test
        @DisplayName("잘못 고른 가게를 끊고 맞는 가게에 이으면 맞는 가게에 옮겨 적는다 — 잘못된 가게의 값은 남는다(한계)")
        void correctionFillsRightStore() {
            Store wrong = persistStore(null);
            Store right = persistStore(null);
            Long memberId = persistApplicant();
            approve(memberId, wrong);

            unlink(memberId, wrong);
            link(memberId, right);

            assertFilledFromApplication(right);
            assertThat(hoursOf(wrong)).hasSize(3);
            assertThat(menusOf(wrong)).hasSize(3);
        }

        @Test
        @DisplayName("맞는 가게를 먼저 잇고 잘못된 가게를 끊으면 옮겨 적지 않는다 — 작업 순서에 따른 한계")
        void linkBeforeUnlinkDoesNotFill() {
            Store wrong = persistStore(null);
            Store right = persistStore(null);
            Long memberId = persistApplicant();
            approve(memberId, wrong);

            link(memberId, right);
            unlink(memberId, wrong);

            assertNothingFilled(right);
        }

        @Test
        @DisplayName("끊었던 같은 가게를 되살릴 때 그 가게가 비어 있으면 채운다 — 대상 가게 자신은 '다른 가게'로 세지 않는다")
        void revivingEmptiedStoreFillsIt() {
            Store store = persistStore(null);
            Long memberId = persistApplicant();
            approve(memberId, store);
            unlink(memberId, store);
            businessHourRepository.deleteAll(hoursOf(store));
            menuRepository.deleteAll(menusOf(store));
            entityManager.flush();
            entityManager.clear();

            link(memberId, store);

            assertThat(hoursOf(store)).hasSize(3);
            assertThat(menusOf(store)).hasSize(3);
        }
    }
    @Nested
    @DisplayName("변경 기록 — 옮겨 적은 항목만, 승인·연결한 관리자 ID 로")
    class ChangeHistory {

        private List<StoreChangeHistoryRow> histories(Store store) {
            return StoreChangeHistoryRow.of(jdbcTemplate, store.getStoreId());
        }

        @Test
        @DisplayName("빈 가게를 승인하면 세 줄 — 신청서 옮겨 적기 경로·관리자 ID, 바뀌기 전은 없음·빈 목록")
        void approvalOfEmptyStoreRecordsThreeFields() {
            Store store = persistStore(null);

            approve(persistApplicant(), store);

            List<StoreChangeHistoryRow> rows = histories(store);
            assertThat(rows).extracting(StoreChangeHistoryRow::field)
                    .containsExactly(StoreChangeField.PHONE, StoreChangeField.BUSINESS_HOURS, StoreChangeField.MENUS);
            assertThat(rows).extracting(StoreChangeHistoryRow::source).containsOnly(StoreChangeSource.SIGNUP_FILL);
            assertThat(rows).extracting(StoreChangeHistoryRow::changedBy).containsOnly(adminId);
            assertThat(rows).extracting(StoreChangeHistoryRow::before).containsExactly(null, json("[]"), json("[]"));
            assertThat(rows.get(0).after()).isEqualTo(json("\"" + APPLIED_STORE_PHONE + "\""));
            // 신청서에 적힌 순서가 아니라 요일 순으로 남는다
            assertThat(rows.get(1).after()).isEqualTo(json("[{\"day\":\"MON\",\"open\":\"11:00\",\"close\":\"15:00\"},"
                    + "{\"day\":\"MON\",\"open\":\"17:00\",\"close\":\"24:00\"},"
                    + "{\"day\":\"FRI\",\"open\":\"21:30\",\"close\":\"02:00\"}]"));
            assertThat(rows.get(2).after().findValuesAsText("name")).containsExactly("나물비빔밥", "가락국수", "다슬기국");
        }

        @Test
        @DisplayName("이미 값이 있어 그대로 둔 항목은 남기지 않는다 — 채운 영업시간·메뉴만 두 줄")
        void keptFieldIsNotRecorded() {
            Store store = persistStore("053-999-9999");

            approve(persistApplicant(), store);

            assertThat(histories(store)).extracting(StoreChangeHistoryRow::field)
                    .containsExactly(StoreChangeField.BUSINESS_HOURS, StoreChangeField.MENUS);
        }

        @Test
        @DisplayName("신청서에 가게 정보가 없으면 아무것도 남기지 않는다")
        void emptyApplicationLeavesNoRecord() {
            Store store = persistStore(null);

            approve(persistApplicant(null, List.of(), List.of()), store);

            assertThat(histories(store)).isEmpty();
        }

        @Test
        @DisplayName("잘못 고른 가게를 끊고 맞는 가게에 다시 이으면 그 연결도 기록한다")
        void correctionLinkIsRecorded() {
            Store wrong = persistStore(null);
            Store right = persistStore(null);
            Long memberId = persistApplicant();
            approve(memberId, wrong);
            unlink(memberId, wrong);

            link(memberId, right);

            assertThat(histories(right))
                    .extracting(StoreChangeHistoryRow::field, StoreChangeHistoryRow::source, StoreChangeHistoryRow::changedBy)
                    .containsExactly(
                            tuple(StoreChangeField.PHONE, StoreChangeSource.SIGNUP_FILL, adminId),
                            tuple(StoreChangeField.BUSINESS_HOURS, StoreChangeSource.SIGNUP_FILL, adminId),
                            tuple(StoreChangeField.MENUS, StoreChangeSource.SIGNUP_FILL, adminId));
        }

        @Test
        @DisplayName("이미 가게가 있는 점주를 두 번째 가게에 이어 옮겨 적지 않으면 그 가게엔 기록도 없다")
        void secondStoreHasNoRecord() {
            Store first = persistStore(null);
            Store second = persistStore(null);
            Long memberId = persistApplicant();
            approve(memberId, first);

            link(memberId, second);

            assertThat(histories(second)).isEmpty();
        }
    }
}
