package com.ktc4.backend.domain.order.service;

import com.ktc4.backend.domain.child.repository.ChildRepository;
import com.ktc4.backend.domain.member.entity.Member;
import com.ktc4.backend.domain.member.entity.OwnerInfo;
import com.ktc4.backend.domain.member.entity.StoreOwner;
import com.ktc4.backend.domain.member.enums.MemberRole;
import com.ktc4.backend.domain.member.service.StoreAccessService;
import com.ktc4.backend.domain.order.dto.ChildOrderCreateRequest;
import com.ktc4.backend.domain.order.dto.OrderResponse;
import com.ktc4.backend.domain.order.entity.ChildOrder;
import com.ktc4.backend.domain.order.enums.OrderStatus;
import com.ktc4.backend.domain.order.enums.ReceiveType;
import com.ktc4.backend.domain.order.repository.ChildOrderRepository;
import com.ktc4.backend.domain.store.entity.Store;
import com.ktc4.backend.domain.store.entity.StoreMenu;
import com.ktc4.backend.domain.store.enums.StoreStatus;
import com.ktc4.backend.domain.store.service.StoreChangeHistoryService;
import com.ktc4.backend.domain.store.service.StoreService;
import com.ktc4.backend.global.dto.PageResponse;
import com.ktc4.backend.global.error.CustomException;
import com.ktc4.backend.global.error.ErrorCode;
import com.ktc4.backend.global.security.AuthMember;
import com.ktc4.backend.global.util.SeoulTime;
import com.ktc4.backend.support.PostgresContainerTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * 주문 서비스를 실제 Postgres 에 붙여서 확인한다 — 메뉴 찾기·진행 중 주문 제약·상태 전이·가게 접근 확인이 한 트랜잭션에서
 * 실제로 맞물리는지 본다. 가게 조회·접근 확인 서비스는 가짜로 바꾸지 않는다.
 */
@Import({OrderService.class, StoreService.class, StoreChangeHistoryService.class, StoreAccessService.class})
@DisplayName("OrderService")
class OrderServiceTest extends PostgresContainerTest {

    private static final AuthMember ADMIN = new AuthMember(1L, MemberRole.ADMIN);
    private static final AuthMember UNLINKED_OWNER = new AuthMember(999_999L, MemberRole.OWNER);

    @Autowired
    private OrderService orderService;

    @Autowired
    private ChildOrderRepository childOrderRepository;

    @Autowired
    private ChildRepository childRepository;

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Store store;
    private Store otherStore;
    private StoreMenu menu;
    private StoreMenu notOnSale;
    private StoreMenu deleted;
    private StoreMenu otherStoreMenu;
    private Long childId;
    private Long otherChildId;

    private Store persistStore(String name) {
        return entityManager.persistAndFlush(Store.builder()
                .name(name).nameNormalized(name)
                .addressRoad("가상특별시 예시구 주문로 1").addressNormalized("가상특별시예시구주문로1")
                .status(StoreStatus.OPEN)
                .build());
    }

    private Long persistChild(String hashSeed) {
        String hash = hashSeed.repeat(64);
        childRepository.insertIfAbsent(hash, LocalDateTime.now());
        return childRepository.findIdByCardHash(hash).orElseThrow();
    }

    private int ownerSeq;

    // 주문은 점주가 연결된 가게에만 받는다 — 테스트 가게마다 점주를 연결해 둔다
    private Member linkOwner(Store target) {
        LocalDateTime linkedAt = LocalDateTime.of(2026, 11, 1, 10, 0);
        Member owner = Member.ownerApplicant("order-owner-" + (++ownerSeq) + "@example.com", "hash", OwnerInfo.builder()
                .bizNo("0000000000").storeName(target.getName()).representativeName("홍길동").phone("01000000000").build());
        owner.approve(linkedAt);
        owner = entityManager.persistAndFlush(owner);
        Member admin = entityManager.persistAndFlush(Member.admin("order-admin-" + ownerSeq + "@example.com", "hash"));
        entityManager.persistAndFlush(StoreOwner.link(target, owner, admin, linkedAt));
        return owner;
    }

    @BeforeEach
    void setUp() {
        store = persistStore("예시분식");
        otherStore = persistStore("샘플카페");
        menu = entityManager.persistAndFlush(StoreMenu.of(store, "후라이드 치킨", true));
        notOnSale = entityManager.persistAndFlush(StoreMenu.of(store, "치킨마요 덮밥", false));
        deleted = StoreMenu.of(store, "단종 메뉴", true);
        deleted.markDeleted(LocalDateTime.now());
        deleted = entityManager.persistAndFlush(deleted);
        otherStoreMenu = entityManager.persistAndFlush(StoreMenu.of(otherStore, "아메리카노", true));
        childId = persistChild("a");
        otherChildId = persistChild("b");
        linkedOwner = linkOwner(store);
        linkOwner(otherStore);
        entityManager.clear();
    }

    private Member linkedOwner;

    private OrderResponse order(Long child, Store at, StoreMenu item) {
        return orderService.create(child, new ChildOrderCreateRequest(at.getStoreId(), item.getMenuId(), ReceiveType.TAKEOUT));
    }

    private OrderStatus statusInDb(Long orderId) {
        entityManager.flush();
        entityManager.clear();
        return childOrderRepository.findById(orderId).orElseThrow().getStatus();
    }

    private void setStatus(Long orderId, OrderStatus status) {
        entityManager.flush();
        jdbcTemplate.update("UPDATE child_order SET status = ? WHERE order_id = ?", status.name(), orderId);
        entityManager.clear();
    }

    private static ErrorCode errorCodeOf(Throwable e) {
        return ((CustomException) e).getErrorCode();
    }

    @Nested
    @DisplayName("아동 — 주문 만들기")
    class Create {

        @Test
        @DisplayName("접수 대기로 만들고, 주문 당시 메뉴 이름과 가게 이름을 담는다 — 아동 번호는 응답에 없다")
        void creates() {
            OrderResponse created = orderService.create(childId,
                    new ChildOrderCreateRequest(store.getStoreId(), menu.getMenuId(), ReceiveType.DINE_IN));

            assertThat(created.status()).isEqualTo(OrderStatus.RECEIVED);
            assertThat(created.storeId()).isEqualTo(store.getStoreId());
            assertThat(created.storeName()).isEqualTo("예시분식");
            assertThat(created.menuName()).isEqualTo("후라이드 치킨");
            assertThat(created.receiveType()).isEqualTo(ReceiveType.DINE_IN);
            assertThat(created.orderedAt()).isNotNull();
            assertThat(created.cookingStartedAt()).isNull();

            ChildOrder saved = childOrderRepository.findById(created.orderId()).orElseThrow();
            assertThat(saved.getChildId()).isEqualTo(childId);
            // 응답은 한국 시각. Postgres 는 마이크로초로 반올림해 저장하므로 그 차이만큼 허용한다
            assertThat(created.orderedAt()).isCloseTo(SeoulTime.toSeoul(saved.getCreatedAt()), within(1, ChronoUnit.MICROS));
        }

        @Test
        @DisplayName("메뉴 이름이 나중에 바뀌어도 주문의 메뉴 이름은 그대로다")
        void menuNameIsSnapshot() {
            Long orderId = order(childId, store, menu).orderId();
            jdbcTemplate.update("UPDATE store_menu SET name = '양념 치킨' WHERE menu_id = ?", menu.getMenuId());
            entityManager.clear();

            assertThat(childOrderRepository.findById(orderId).orElseThrow().getMenuName()).isEqualTo("후라이드 치킨");
        }

        @Test
        @DisplayName("다른 가게의 메뉴·지운 메뉴·없는 메뉴·없는 가게는 404 menu-not-found 로 묶는다")
        void menuNotFound() {
            List<ChildOrderCreateRequest> requests = List.of(
                    new ChildOrderCreateRequest(store.getStoreId(), otherStoreMenu.getMenuId(), ReceiveType.TAKEOUT),
                    new ChildOrderCreateRequest(store.getStoreId(), deleted.getMenuId(), ReceiveType.TAKEOUT),
                    new ChildOrderCreateRequest(store.getStoreId(), 999_999L, ReceiveType.TAKEOUT),
                    new ChildOrderCreateRequest(999_999L, menu.getMenuId(), ReceiveType.TAKEOUT));

            for (ChildOrderCreateRequest request : requests) {
                assertThatThrownBy(() -> orderService.create(childId, request))
                        .as(request.toString())
                        .satisfies(e -> assertThat(errorCodeOf(e)).isEqualTo(ErrorCode.MENU_NOT_FOUND));
            }
            assertThat(childOrderRepository.count()).isZero();
        }

        @Test
        @DisplayName("오늘 판매하지 않는 메뉴는 409 menu-not-on-sale")
        void menuNotOnSale() {
            assertThatThrownBy(() -> order(childId, store, notOnSale))
                    .satisfies(e -> assertThat(errorCodeOf(e)).isEqualTo(ErrorCode.MENU_NOT_ON_SALE));
        }

        @Test
        @DisplayName("점주가 연결되지 않은 가게에는 주문할 수 없다 — 409 store-not-orderable, 주문은 생기지 않는다")
        void storeWithoutOwner() {
            Store ownerless = persistStore("빈가게");
            StoreMenu ownerlessMenu = entityManager.persistAndFlush(StoreMenu.of(ownerless, "떡볶이", true));
            entityManager.clear();

            assertThatThrownBy(() -> order(childId, ownerless, ownerlessMenu))
                    .satisfies(e -> assertThat(errorCodeOf(e)).isEqualTo(ErrorCode.STORE_NOT_ORDERABLE));
            assertThat(childOrderRepository.count()).isZero();
        }

        @Test
        @DisplayName("진행 중 주문이 있으면 409 active-order-exists — 다른 가게라도. 연타도 이 경우다")
        void activeOrderExists() {
            order(childId, store, menu);

            assertThatThrownBy(() -> order(childId, otherStore, otherStoreMenu))
                    .satisfies(e -> assertThat(errorCodeOf(e)).isEqualTo(ErrorCode.ACTIVE_ORDER_EXISTS));
        }
    }

    @Nested
    @DisplayName("아동 — 진행 중 주문·취소")
    class ChildSide {

        @Test
        @DisplayName("진행 중 주문은 아이마다 자기 것만 보인다 — 두 아이가 모두 주문 중이어도")
        void activeOrderIsPerChild() {
            Long mine = order(childId, store, menu).orderId();
            Long others = order(otherChildId, otherStore, otherStoreMenu).orderId();

            assertThat(orderService.findActive(childId)).map(OrderResponse::orderId).contains(mine);
            assertThat(orderService.findActive(otherChildId)).map(OrderResponse::orderId).contains(others);
            assertThat(orderService.findActive(persistChild("z"))).isEmpty();
        }

        @Test
        @DisplayName("진행 중 주문은 접수 대기·조리 중이고, 끝나거나 취소되면 없다")
        void activeOrder() {
            assertThat(orderService.findActive(childId)).isEmpty();

            Long orderId = order(childId, store, menu).orderId();
            assertThat(orderService.findActive(childId)).map(OrderResponse::orderId).contains(orderId);

            orderService.startCooking(store.getStoreId(), orderId, ADMIN);
            assertThat(orderService.findActive(childId)).map(OrderResponse::status).contains(OrderStatus.COOKING);

            orderService.complete(store.getStoreId(), orderId, ADMIN);
            assertThat(orderService.findActive(childId)).isEmpty();
            assertThat(orderService.findActive(otherChildId)).isEmpty();
        }

        @Test
        @DisplayName("자기 주문을 접수 대기일 때 취소한다 — 취소 시각이 남고 다시 주문할 수 있다")
        void cancelsOwnOrder() {
            Long orderId = order(childId, store, menu).orderId();

            OrderResponse canceled = orderService.cancelByChild(childId, orderId);

            assertThat(canceled.status()).isEqualTo(OrderStatus.CANCELED);
            assertThat(canceled.canceledAt()).isNotNull();
            assertThat(statusInDb(orderId)).isEqualTo(OrderStatus.CANCELED);
            assertThat(canceled.canceledAt()).isCloseTo(
                    SeoulTime.toSeoul(childOrderRepository.findById(orderId).orElseThrow().getCanceledAt()),
                    within(1, ChronoUnit.MICROS));
            assertThat(order(childId, store, menu).status()).isEqualTo(OrderStatus.RECEIVED);
        }

        @Test
        @DisplayName("취소를 두 번 눌러도 두 번째도 성공이고, 처음 취소 시각이 남는다")
        void cancelIsIdempotent() {
            Long orderId = order(childId, store, menu).orderId();
            LocalDateTime firstCanceledAt = orderService.cancelByChild(childId, orderId).canceledAt();
            entityManager.flush();
            entityManager.clear();

            OrderResponse again = orderService.cancelByChild(childId, orderId);

            assertThat(again.status()).isEqualTo(OrderStatus.CANCELED);
            // 처음 응답은 메모리의 시각, 두 번째는 DB 에서 읽은 시각(마이크로초 반올림)이라 그만큼은 허용한다
            assertThat(again.canceledAt()).isCloseTo(firstCanceledAt, within(1, ChronoUnit.MICROS));
        }

        @Test
        @DisplayName("남의 주문·없는 주문 취소는 404 order-not-found — 남의 주문인지 알려 주지 않는다. 그 주문은 그대로")
        void cannotCancelOthers() {
            Long othersOrder = order(otherChildId, store, menu).orderId();

            assertThatThrownBy(() -> orderService.cancelByChild(childId, othersOrder))
                    .satisfies(e -> assertThat(errorCodeOf(e)).isEqualTo(ErrorCode.ORDER_NOT_FOUND));
            assertThatThrownBy(() -> orderService.cancelByChild(childId, 999_999L))
                    .satisfies(e -> assertThat(errorCodeOf(e)).isEqualTo(ErrorCode.ORDER_NOT_FOUND));
            assertThat(statusInDb(othersOrder)).isEqualTo(OrderStatus.RECEIVED);
        }
    }

    @Nested
    @DisplayName("점주 — 목록·상태 바꾸기")
    class StoreSide {

        @Test
        @DisplayName("조리 시작 → 조리 완료, 각 시각이 남는다")
        void cookAndComplete() {
            Long orderId = order(childId, store, menu).orderId();

            OrderResponse cooking = orderService.startCooking(store.getStoreId(), orderId, ADMIN);
            assertThat(cooking.status()).isEqualTo(OrderStatus.COOKING);
            assertThat(cooking.cookingStartedAt()).isNotNull();

            OrderResponse done = orderService.complete(store.getStoreId(), orderId, ADMIN);
            assertThat(done.status()).isEqualTo(OrderStatus.DONE);
            assertThat(done.completedAt()).isNotNull();
            assertThat(statusInDb(orderId)).isEqualTo(OrderStatus.DONE);

            // 응답의 시각은 모두 저장된 시각을 한국 시각으로 바꾼 값이다 — 서버가 UTC 여도(CI 는 UTC 로 돈다)
            ChildOrder saved = childOrderRepository.findById(orderId).orElseThrow();
            assertThat(cooking.cookingStartedAt())
                    .isCloseTo(SeoulTime.toSeoul(saved.getCookingStartedAt()), within(1, ChronoUnit.MICROS));
            assertThat(done.completedAt())
                    .isCloseTo(SeoulTime.toSeoul(saved.getCompletedAt()), within(1, ChronoUnit.MICROS));
        }

        @Test
        @DisplayName("다른 가게 경로로 이 주문을 다루면 404 order-not-found, 주문은 그대로")
        void wrongStoreIsNotFound() {
            Long orderId = order(childId, store, menu).orderId();

            Long other = otherStore.getStoreId();
            List<Runnable> calls = List.of(
                    () -> orderService.startCooking(other, orderId, ADMIN),
                    () -> orderService.complete(other, orderId, ADMIN),
                    () -> orderService.cancelByStore(other, orderId, ADMIN));
            for (Runnable call : calls) {
                assertThatThrownBy(call::run)
                        .satisfies(e -> assertThat(errorCodeOf(e)).isEqualTo(ErrorCode.ORDER_NOT_FOUND));
            }
            assertThat(statusInDb(orderId)).isEqualTo(OrderStatus.RECEIVED);
        }

        @Test
        @DisplayName("연결되지 않은 점주는 목록·상태 바꾸기 모두 403 — 주문이 있는지도 알려 주지 않는다")
        void unlinkedOwnerForbidden() {
            Long orderId = order(childId, store, menu).orderId();
            Long storeId = store.getStoreId();

            List<Function<AuthMember, Object>> calls = List.of(
                    member -> orderService.listForStore(storeId, member),
                    member -> orderService.startCooking(storeId, orderId, member),
                    member -> orderService.complete(storeId, orderId, member),
                    member -> orderService.cancelByStore(storeId, orderId, member));
            for (Function<AuthMember, Object> call : calls) {
                assertThatThrownBy(() -> call.apply(UNLINKED_OWNER))
                        .satisfies(e -> assertThat(errorCodeOf(e)).isEqualTo(ErrorCode.FORBIDDEN));
            }
            assertThat(statusInDb(orderId)).isEqualTo(OrderStatus.RECEIVED);
        }

        @Test
        @DisplayName("연결된 점주는 자기 가게 주문을 처리한다")
        void linkedOwnerCanHandle() {
            AuthMember owner = new AuthMember(linkedOwner.getMemberId(), MemberRole.OWNER);
            Long orderId = order(childId, store, menu).orderId();

            assertThat(orderService.listForStore(store.getStoreId(), owner)).extracting(OrderResponse::orderId)
                    .containsExactly(orderId);
            assertThat(orderService.startCooking(store.getStoreId(), orderId, owner).status())
                    .isEqualTo(OrderStatus.COOKING);
        }

        @Test
        @DisplayName("목록에는 어제 남은 조리 중 주문도 보인다 — 그래야 끝내 줄 수 있고, 끝내면 그 아이가 다시 주문한다")
        void yesterdaysActiveOrderStaysVisible() {
            Long orderId = order(childId, store, menu).orderId();
            orderService.startCooking(store.getStoreId(), orderId, ADMIN);
            entityManager.flush();
            jdbcTemplate.update("UPDATE child_order SET created_at = ? WHERE order_id = ?",
                    LocalDateTime.now().minusDays(2), orderId);
            entityManager.clear();

            assertThat(orderService.listForStore(store.getStoreId(), ADMIN)).extracting(OrderResponse::orderId)
                    .containsExactly(orderId);

            orderService.complete(store.getStoreId(), orderId, ADMIN);
            // 실제 요청은 트랜잭션이 따로라 완료가 먼저 커밋된다. 한 테스트 트랜잭션에서는 Hibernate 가 새 주문 INSERT 를
            // 완료 UPDATE 보다 먼저 보내므로, 완료를 먼저 내보낸다
            entityManager.flush();
            assertThat(order(childId, store, menu).status()).isEqualTo(OrderStatus.RECEIVED);
        }

        @Test
        @DisplayName("목록에서 지난날 끝난 주문은 빠지고, 오늘 취소된 주문은 남는다")
        void listDropsOldFinishedOrders() {
            Long oldDone = order(childId, store, menu).orderId();
            orderService.startCooking(store.getStoreId(), oldDone, ADMIN);
            orderService.complete(store.getStoreId(), oldDone, ADMIN);
            entityManager.flush();
            jdbcTemplate.update("UPDATE child_order SET created_at = ? WHERE order_id = ?",
                    LocalDateTime.now().minusDays(2), oldDone);
            Long todayCanceled = order(childId, store, menu).orderId();
            orderService.cancelByStore(store.getStoreId(), todayCanceled, ADMIN);
            entityManager.clear();

            assertThat(orderService.listForStore(store.getStoreId(), ADMIN)).extracting(OrderResponse::orderId)
                    .containsExactly(todayCanceled);
        }
    }

    enum Action { START_COOKING, COMPLETE, CANCEL_BY_STORE, CANCEL_BY_CHILD }

    private static final Set<String> ALLOWED = Set.of(
            "RECEIVED:START_COOKING", "COOKING:COMPLETE", "RECEIVED:CANCEL_BY_STORE", "RECEIVED:CANCEL_BY_CHILD");
    // 이미 취소된 주문을 다시 취소하면 바꾸지 않고 성공한다(취소 버튼 두 번 누름)
    private static final Set<String> NO_OP = Set.of("CANCELED:CANCEL_BY_STORE", "CANCELED:CANCEL_BY_CHILD");

    static Stream<Arguments> transitions() {
        return Arrays.stream(OrderStatus.values())
                .flatMap(from -> Arrays.stream(Action.values()).map(action -> Arguments.of(from, action)));
    }

    @ParameterizedTest(name = "[{index}] {0} 에서 {1}")
    @MethodSource("transitions")
    @DisplayName("상태 전이 표 전부 — 허용된 넷만 바뀌고, 취소된 주문 다시 취소는 그대로 성공, 나머지는 409 이고 상태는 그대로")
    void transitionMatrix(OrderStatus from, Action action) {
        Long orderId = order(childId, store, menu).orderId();
        setStatus(orderId, from);
        Long storeId = store.getStoreId();

        Runnable call = switch (action) {
            case START_COOKING -> () -> orderService.startCooking(storeId, orderId, ADMIN);
            case COMPLETE -> () -> orderService.complete(storeId, orderId, ADMIN);
            case CANCEL_BY_STORE -> () -> orderService.cancelByStore(storeId, orderId, ADMIN);
            case CANCEL_BY_CHILD -> () -> orderService.cancelByChild(childId, orderId);
        };

        if (ALLOWED.contains(from + ":" + action)) {
            call.run();
            assertThat(statusInDb(orderId)).isNotEqualTo(from);
        } else if (NO_OP.contains(from + ":" + action)) {
            call.run();
            assertThat(statusInDb(orderId)).isEqualTo(from);
        } else {
            assertThatThrownBy(call::run)
                    .satisfies(e -> assertThat(errorCodeOf(e)).isEqualTo(ErrorCode.ORDER_STATUS_CONFLICT));
            assertThat(statusInDb(orderId)).isEqualTo(from);
        }
    }

    @Nested
    @DisplayName("체크인 짝 찾기·관리자 목록")
    class Lookups {

        @Test
        @DisplayName("이 아동이 이 가게에 낸 접수 대기 주문만 찾는다")
        void receivedAt() {
            Long orderId = order(childId, store, menu).orderId();

            assertThat(orderService.findReceivedAt(childId, store.getStoreId())).map(OrderResponse::orderId).contains(orderId);
            assertThat(orderService.findReceivedAt(childId, otherStore.getStoreId())).isEmpty();
            assertThat(orderService.findReceivedAt(otherChildId, store.getStoreId())).isEmpty();

            orderService.startCooking(store.getStoreId(), orderId, ADMIN);
            assertThat(orderService.findReceivedAt(childId, store.getStoreId())).isEmpty();
        }

        @Test
        @DisplayName("관리자 목록은 최근 순 페이지이고 가게 이름이 담긴다")
        void adminPage() {
            Long first = order(childId, store, menu).orderId();
            Long second = order(otherChildId, otherStore, otherStoreMenu).orderId();
            entityManager.clear();

            PageResponse<OrderResponse> page = orderService.listAll(0, 1);

            assertThat(page.content()).extracting(OrderResponse::orderId).containsExactly(second);
            assertThat(page.content()).extracting(OrderResponse::storeName).containsExactly("샘플카페");
            assertThat(page.totalElements()).isEqualTo(2);
            assertThat(page.hasNext()).isTrue();
            assertThat(orderService.listAll(1, 1).content()).extracting(OrderResponse::orderId).containsExactly(first);
        }
    }
}
