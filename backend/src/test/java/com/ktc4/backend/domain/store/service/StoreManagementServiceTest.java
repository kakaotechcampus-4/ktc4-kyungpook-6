package com.ktc4.backend.domain.store.service;

import com.ktc4.backend.domain.member.entity.Member;
import com.ktc4.backend.domain.member.entity.OwnerInfo;
import com.ktc4.backend.domain.member.entity.StoreOwner;
import com.ktc4.backend.domain.member.enums.MemberRole;
import com.ktc4.backend.domain.member.service.StoreAccessService;
import com.ktc4.backend.domain.store.dto.BusinessHourRequest;
import com.ktc4.backend.domain.store.dto.BusinessHourResponse;
import com.ktc4.backend.domain.store.dto.MenuRequest;
import com.ktc4.backend.domain.store.dto.MenuResponse;
import com.ktc4.backend.domain.store.dto.OperatingInfoRequest;
import com.ktc4.backend.domain.store.dto.StoreDetailResponse;
import com.ktc4.backend.domain.store.entity.Store;
import com.ktc4.backend.domain.store.entity.StoreBusinessHour;
import com.ktc4.backend.domain.store.entity.StoreMenu;
import com.ktc4.backend.domain.store.enums.BusinessDay;
import com.ktc4.backend.domain.store.enums.StoreStatus;
import com.ktc4.backend.global.error.CustomException;
import com.ktc4.backend.global.error.ErrorCode;
import com.ktc4.backend.global.security.AuthMember;
import com.ktc4.backend.support.PostgresContainerTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/**
 * 가게 상세 조회와 운영 정보 저장의 규칙을 실제 DB 로 본다 — 누가 다룰 수 있는지, 상세를 어떤 순서로 보여 주는지,
 * 어떤 {@code menuId} 를 거절하는지. 저장이 실제로 커밋되는지·되돌아가는지는 {@link StoreManagementCommitTest} 가 본다.
 * 계정·가게 정보는 모두 가짜 값이다.
 */
@Import({StoreManagementService.class, StoreAccessService.class, StoreService.class})
@DisplayName("StoreManagementService")
class StoreManagementServiceTest extends PostgresContainerTest {

    private static final LocalDateTime LINKED_AT = LocalDateTime.of(2026, 10, 14, 10, 0);
    private static final long MISSING_STORE_ID = 999_999L;

    @Autowired
    private StoreManagementService storeManagementService;

    @Autowired
    private TestEntityManager entityManager;

    private Member admin;
    private Store store;
    private Store otherStore;
    private AuthMember owner;
    private AuthMember adminMember;

    @BeforeEach
    void setUp() {
        admin = entityManager.persistAndFlush(Member.admin("admin@example.com", "hash"));
        adminMember = new AuthMember(admin.getMemberId(), MemberRole.ADMIN);
        store = persistStore("예시분식", "053-000-0001");
        otherStore = persistStore("다른가게", "053-000-0002");
        Member ownerMember = Member.ownerApplicant("owner@example.com", "hash", OwnerInfo.builder()
                .bizNo("1234567890").storeName("예시분식").representativeName("홍길동").phone("01000000000")
                .build());
        ownerMember.approve(LINKED_AT);
        entityManager.persistAndFlush(ownerMember);
        entityManager.persistAndFlush(StoreOwner.link(store, ownerMember, admin, LINKED_AT));
        owner = new AuthMember(ownerMember.getMemberId(), MemberRole.OWNER);
    }

    private Store persistStore(String name, String phone) {
        return entityManager.persistAndFlush(Store.builder()
                .name(name).nameNormalized(name)
                .addressRoad("가상특별시 예시구 샘플로 123").addressNormalized("가상특별시예시구샘플로123")
                .status(StoreStatus.OPEN).phone(phone)
                .build());
    }

    private StoreMenu persistMenu(Store target, String name, boolean onSale) {
        return entityManager.persistAndFlush(StoreMenu.of(target, name, onSale));
    }

    private static OperatingInfoRequest request(List<MenuRequest> menus) {
        return new OperatingInfoRequest("053-123-4567",
                List.of(new BusinessHourRequest(BusinessDay.MON, "11:00", "21:00")), menus);
    }

    private static void assertRejectedWith(Runnable call, ErrorCode errorCode) {
        assertThatThrownBy(call::run).isInstanceOf(CustomException.class).extracting("errorCode").isEqualTo(errorCode);
    }

    @Nested
    @DisplayName("누가 다룰 수 있나 — 상세와 저장 둘 다")
    class Access {

        @Test
        @DisplayName("연결되지 않은 점주는 있는 가게든 없는 가게든 403 — 가게를 읽기 전에 막아 있는지도 알려 주지 않는다")
        void unlinkedOwnerIsForbiddenBeforeStoreLookup() {
            for (Long storeId : List.of(otherStore.getStoreId(), MISSING_STORE_ID)) {
                assertRejectedWith(() -> storeManagementService.getDetail(storeId, owner), ErrorCode.FORBIDDEN);
                assertRejectedWith(() -> storeManagementService.save(storeId, request(List.of()), owner),
                        ErrorCode.FORBIDDEN);
            }
        }

        @Test
        @DisplayName("관리자는 없는 가게에 404")
        void adminGetsNotFoundForMissingStore() {
            assertRejectedWith(() -> storeManagementService.getDetail(MISSING_STORE_ID, adminMember),
                    ErrorCode.STORE_NOT_FOUND);
            assertRejectedWith(() -> storeManagementService.save(MISSING_STORE_ID, request(List.of()), adminMember),
                    ErrorCode.STORE_NOT_FOUND);
        }

        @Test
        @DisplayName("로그인 정보가 없으면 401")
        void missingLoginIsUnauthorized() {
            assertRejectedWith(() -> storeManagementService.getDetail(store.getStoreId(), null), ErrorCode.UNAUTHORIZED);
            assertRejectedWith(() -> storeManagementService.save(store.getStoreId(), request(List.of()), null),
                    ErrorCode.UNAUTHORIZED);
        }

        @Test
        @DisplayName("관리자는 어느 가게든 상세를 본다")
        void adminSeesAnyStore() {
            assertThat(storeManagementService.getDetail(otherStore.getStoreId(), adminMember).name()).isEqualTo("다른가게");
        }
    }

    @Nested
    @DisplayName("상세")
    class Detail {

        @Test
        @DisplayName("영업시간은 월 → 일, 같은 요일은 여는 시간 순 — 넣은 순서도 알파벳순(FRI·MON·SUN·TUE)도 아니다")
        void sortsBusinessHoursByWeek() {
            entityManager.persist(StoreBusinessHour.of(store, BusinessDay.SUN, 600, 840));
            entityManager.persist(StoreBusinessHour.of(store, BusinessDay.MON, 1020, 1440));
            entityManager.persist(StoreBusinessHour.of(store, BusinessDay.FRI, 1260, 120));
            entityManager.persist(StoreBusinessHour.of(store, BusinessDay.MON, 660, 900));
            entityManager.persist(StoreBusinessHour.of(store, BusinessDay.TUE, 0, 1440));
            entityManager.persist(StoreBusinessHour.of(otherStore, BusinessDay.WED, 600, 900));   // 다른 가게 것은 섞이지 않는다
            entityManager.flush();
            entityManager.clear();

            assertThat(storeManagementService.getDetail(store.getStoreId(), owner).businessHours())
                    .containsExactly(
                            new BusinessHourResponse(BusinessDay.MON, "11:00", "15:00"),
                            new BusinessHourResponse(BusinessDay.MON, "17:00", "24:00"),
                            new BusinessHourResponse(BusinessDay.TUE, "00:00", "24:00"),
                            new BusinessHourResponse(BusinessDay.FRI, "21:00", "02:00"),
                            new BusinessHourResponse(BusinessDay.SUN, "10:00", "14:00"));
        }

        @Test
        @DisplayName("메뉴는 지운 것을 빼고 등록 순(menuId 순) — 이름 순이 아니다")
        void listsLiveMenusInRegistrationOrder() {
            StoreMenu first = persistMenu(store, "하 마지막 이름", true);
            StoreMenu deleted = persistMenu(store, "나 지운 메뉴", true);
            StoreMenu last = persistMenu(store, "가 첫 이름", false);
            deleted.markDeleted(LINKED_AT);
            entityManager.flush();
            entityManager.clear();

            assertThat(storeManagementService.getDetail(store.getStoreId(), owner).menus()).containsExactly(
                    new MenuResponse(first.getMenuId(), "하 마지막 이름", true),
                    new MenuResponse(last.getMenuId(), "가 첫 이름", false));
        }

        @Test
        @DisplayName("상호명·주소·가게 전화를 그대로 보여 주고, 영업시간·메뉴가 없으면 빈 목록이다")
        void emptyStore() {
            StoreDetailResponse detail = storeManagementService.getDetail(store.getStoreId(), owner);

            assertThat(detail.storeId()).isEqualTo(store.getStoreId());
            assertThat(detail.name()).isEqualTo("예시분식");
            assertThat(detail.addressRoad()).isEqualTo("가상특별시 예시구 샘플로 123");
            assertThat(detail.phone()).isEqualTo("053-000-0001");
            assertThat(detail.businessHours()).isEmpty();
            assertThat(detail.menus()).isEmpty();
        }

        @ParameterizedTest(name = "가게 전화 \"{0}\"")
        @NullAndEmptySource
        @ValueSource(strings = "   ")
        @DisplayName("가게 전화가 없거나 빈 문자열이면 null 로 보낸다 — 화면에는 \"없음\" 하나")
        void blankPhoneIsNull(String phone) {
            Store blank = persistStore("전화없는가게", phone);

            assertThat(storeManagementService.getDetail(blank.getStoreId(), adminMember).phone()).isNull();
        }
    }

    @Nested
    @DisplayName("저장이 거절하는 menuId — 이 가게의 지우지 않은 메뉴가 아니면 400")
    class RejectedMenuIds {

        @Test
        @DisplayName("남의 가게 메뉴 → 400, 그 메뉴는 그대로")
        void foreignMenu() {
            StoreMenu foreign = persistMenu(otherStore, "남의 메뉴", true);

            assertRejectedWith(() -> storeManagementService.save(store.getStoreId(),
                    request(List.of(new MenuRequest(foreign.getMenuId(), "가로채기", false))), owner),
                    ErrorCode.INVALID_REQUEST);
            entityManager.clear();
            StoreMenu reread = entityManager.find(StoreMenu.class, foreign.getMenuId());
            assertThat(reread.getName()).isEqualTo("남의 메뉴");
            assertThat(reread.isOnSale()).isTrue();
        }

        @Test
        @DisplayName("지운 메뉴 → 400 (되살리지 않는다)")
        void deletedMenu() {
            StoreMenu deleted = persistMenu(store, "지운 메뉴", true);
            deleted.markDeleted(LINKED_AT);
            entityManager.flush();

            assertRejectedWith(() -> storeManagementService.save(store.getStoreId(),
                    request(List.of(new MenuRequest(deleted.getMenuId(), "되살리기", true))), owner),
                    ErrorCode.INVALID_REQUEST);
        }

        @Test
        @DisplayName("없는 메뉴 → 400 (새로 만들지 않는다)")
        void missingMenu() {
            assertRejectedWith(() -> storeManagementService.save(store.getStoreId(),
                    request(List.of(new MenuRequest(999_999L, "없는 메뉴", true))), owner),
                    ErrorCode.INVALID_REQUEST);
            entityManager.clear();
            assertThat(entityManager.getEntityManager()
                    .createQuery("select count(m) from StoreMenu m where m.store.storeId = :id", Long.class)
                    .setParameter("id", store.getStoreId()).getSingleResult()).isZero();
        }
    }

    @Test
    @DisplayName("저장 결과에는 가게 전화·메뉴 이름의 앞뒤 공백이 지워져 있다 — 가입 저장과 같다")
    void savesStrippedText() {
        StoreDetailResponse saved = storeManagementService.save(store.getStoreId(), new OperatingInfoRequest(
                " 053-123-4567 ", List.of(new BusinessHourRequest(BusinessDay.MON, "11:00", "21:00")),
                List.of(new MenuRequest(null, "  라면 ", true))), owner);

        assertThat(saved.phone()).isEqualTo("053-123-4567");
        assertThat(saved.menus()).extracting(MenuResponse::name, MenuResponse::onSale).containsExactly(tuple("라면", true));
    }
}
