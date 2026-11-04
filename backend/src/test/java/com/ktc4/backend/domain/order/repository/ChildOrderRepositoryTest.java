package com.ktc4.backend.domain.order.repository;

import com.ktc4.backend.domain.child.repository.ChildRepository;
import com.ktc4.backend.domain.order.entity.ChildOrder;
import com.ktc4.backend.domain.order.enums.OrderStatus;
import com.ktc4.backend.domain.order.enums.ReceiveType;
import com.ktc4.backend.domain.order.service.OrderService;
import com.ktc4.backend.domain.store.entity.Store;
import com.ktc4.backend.domain.store.entity.StoreMenu;
import com.ktc4.backend.domain.store.enums.StoreStatus;
import com.ktc4.backend.support.PostgresContainerTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 주문 표를 실제 Postgres 에 붙여서 확인한다. 저장한 뒤 {@code clear} 하고 다시 읽는다 — 1차 캐시가 아니라 DB 를 읽게.
 */
@DisplayName("ChildOrderRepository")
class ChildOrderRepositoryTest extends PostgresContainerTest {

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

    @BeforeEach
    void setUp() {
        store = persistStore("예시분식");
        otherStore = persistStore("샘플카페");
        menu = entityManager.persistAndFlush(StoreMenu.of(store, "후라이드 치킨", true));
        otherStoreMenu = entityManager.persistAndFlush(StoreMenu.of(otherStore, "아메리카노", true));
        childId = persistChild("a");
        otherChildId = persistChild("b");
    }

    private ChildOrder save(Long child, Store at, StoreMenu item) {
        return childOrderRepository.saveAndFlush(ChildOrder.receive(child, at, item, ReceiveType.TAKEOUT));
    }

    private void setStatus(ChildOrder order, OrderStatus status) {
        jdbcTemplate.update("UPDATE child_order SET status = ? WHERE order_id = ?", status.name(), order.getOrderId());
    }

    private void setCreatedAt(ChildOrder order, LocalDateTime createdAt) {
        jdbcTemplate.update("UPDATE child_order SET created_at = ? WHERE order_id = ?", createdAt, order.getOrderId());
    }

    @Test
    @DisplayName("저장한 주문을 다시 읽으면 아동·가게·메뉴·메뉴 이름·받는 방법·접수 대기가 그대로다")
    void roundTrip() {
        Long orderId = save(childId, store, menu).getOrderId();
        entityManager.clear();

        ChildOrder found = childOrderRepository.findById(orderId).orElseThrow();
        assertThat(found.getChildId()).isEqualTo(childId);
        assertThat(found.getStore().getStoreId()).isEqualTo(store.getStoreId());
        assertThat(found.getMenuId()).isEqualTo(menu.getMenuId());
        assertThat(found.getMenuName()).isEqualTo("후라이드 치킨");
        assertThat(found.getReceiveType()).isEqualTo(ReceiveType.TAKEOUT);
        assertThat(found.getStatus()).isEqualTo(OrderStatus.RECEIVED);
        assertThat(found.getCreatedAt()).isNotNull();
        assertThat(found.getCookingStartedAt()).isNull();
    }

    @ParameterizedTest
    @EnumSource(OrderStatus.class)
    @DisplayName("상태 값은 모두 DB 제약을 지나 그대로 읽힌다")
    void statusRoundTrip(OrderStatus status) {
        ChildOrder order = save(childId, store, menu);
        setStatus(order, status);
        entityManager.clear();

        assertThat(childOrderRepository.findById(order.getOrderId()).orElseThrow().getStatus()).isEqualTo(status);
    }

    @ParameterizedTest
    @EnumSource(ReceiveType.class)
    @DisplayName("받는 방법 값은 모두 DB 제약을 지나 그대로 읽힌다")
    void receiveTypeRoundTrip(ReceiveType receiveType) {
        Long orderId = childOrderRepository.saveAndFlush(ChildOrder.receive(childId, store, menu, receiveType)).getOrderId();
        entityManager.clear();

        assertThat(childOrderRepository.findById(orderId).orElseThrow().getReceiveType()).isEqualTo(receiveType);
    }

    @Test
    @DisplayName("같은 아동의 진행 중 주문이 둘이면 거절되고, 그 제약 이름을 서비스가 알아본다")
    void rejectsSecondActiveOrder() {
        save(childId, store, menu);

        assertThatThrownBy(() -> save(childId, otherStore, otherStoreMenu))
                .isInstanceOfSatisfying(DataIntegrityViolationException.class,
                        e -> assertThat(OrderService.isActiveOrderViolation(e)).isTrue());
    }

    @Test
    @DisplayName("조리 중인 주문도 진행 중이라 새 주문이 거절된다")
    void cookingOrderStillBlocks() {
        setStatus(save(childId, store, menu), OrderStatus.COOKING);

        assertThatThrownBy(() -> save(childId, store, menu)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, names = {"DONE", "CANCELED"})
    @DisplayName("앞 주문이 끝났거나 취소됐으면 새 주문을 받는다 — 제약은 진행 중 주문만 본다")
    void finishedOrderDoesNotBlock(OrderStatus finished) {
        setStatus(save(childId, store, menu), finished);

        assertThat(save(childId, store, menu).getOrderId()).isNotNull();
    }

    @Test
    @DisplayName("다른 아동의 진행 중 주문과는 상관없다")
    void otherChildrenIndependent() {
        save(childId, store, menu);

        assertThat(save(otherChildId, store, menu).getOrderId()).isNotNull();
    }

    @Test
    @DisplayName("다른 제약 위반(없는 아동)은 진행 중 주문 위반으로 보지 않는다")
    void otherViolationIsNotActiveOrder() {
        assertThatThrownBy(() -> save(999_999_999L, store, menu))
                .isInstanceOfSatisfying(DataIntegrityViolationException.class,
                        e -> assertThat(OrderService.isActiveOrderViolation(e)).isFalse());
    }

    @Test
    @DisplayName("점주 목록: 이 가게의 오늘 주문 전부와, 날짜와 상관없이 끝나지 않은 주문 — 최근 순")
    void storeListTodayPlusActive() {
        LocalDateTime startOfToday = LocalDateTime.of(2026, 11, 4, 0, 0);
        ChildOrder yesterdayCooking = save(childId, store, menu);
        setStatus(yesterdayCooking, OrderStatus.COOKING);
        setCreatedAt(yesterdayCooking, startOfToday.minusHours(5));

        Long thirdChild = persistChild("c");
        ChildOrder yesterdayDone = save(thirdChild, store, menu);
        setStatus(yesterdayDone, OrderStatus.DONE);
        setCreatedAt(yesterdayDone, startOfToday.minusMinutes(1));

        ChildOrder todayCanceled = save(otherChildId, store, menu);
        setStatus(todayCanceled, OrderStatus.CANCELED);
        setCreatedAt(todayCanceled, startOfToday);

        Long fourthChild = persistChild("d");
        ChildOrder todayReceived = save(fourthChild, store, menu);
        setCreatedAt(todayReceived, startOfToday.plusHours(3));

        Long fifthChild = persistChild("e");
        save(fifthChild, otherStore, otherStoreMenu);   // 다른 가게
        entityManager.clear();

        List<Long> ids = childOrderRepository.findForStore(store.getStoreId(), startOfToday, OrderStatus.ACTIVE)
                .stream().map(ChildOrder::getOrderId).toList();

        assertThat(ids).containsExactly(todayReceived.getOrderId(), todayCanceled.getOrderId(),
                yesterdayCooking.getOrderId());
    }

    @Test
    @DisplayName("체크인 짝: 이 아동이 이 가게에 낸 접수 대기 주문만 찾는다")
    void receivedOrderForCheckIn() {
        ChildOrder order = save(childId, store, menu);
        entityManager.clear();

        assertThat(childOrderRepository.findFirstByChildIdAndStore_StoreIdAndStatus(
                childId, store.getStoreId(), OrderStatus.RECEIVED)).map(ChildOrder::getOrderId).contains(order.getOrderId());
        assertThat(childOrderRepository.findFirstByChildIdAndStore_StoreIdAndStatus(
                childId, otherStore.getStoreId(), OrderStatus.RECEIVED)).isEmpty();
        assertThat(childOrderRepository.findFirstByChildIdAndStore_StoreIdAndStatus(
                otherChildId, store.getStoreId(), OrderStatus.RECEIVED)).isEmpty();

        setStatus(order, OrderStatus.COOKING);
        assertThat(childOrderRepository.findFirstByChildIdAndStore_StoreIdAndStatus(
                childId, store.getStoreId(), OrderStatus.RECEIVED)).isEmpty();
    }

    @Test
    @DisplayName("잠그는 조회는 주문 번호와 아동·가게가 함께 맞을 때만 찾는다 — 남의 주문은 없는 것과 같다")
    void lockingFindersMatchOwner() {
        ChildOrder order = save(childId, store, menu);
        entityManager.clear();

        assertThat(childOrderRepository.findByIdAndChildIdForUpdate(order.getOrderId(), childId)).isPresent();
        assertThat(childOrderRepository.findByIdAndChildIdForUpdate(order.getOrderId(), otherChildId)).isEmpty();
        assertThat(childOrderRepository.findByIdAndStoreIdForUpdate(order.getOrderId(), store.getStoreId())).isPresent();
        assertThat(childOrderRepository.findByIdAndStoreIdForUpdate(order.getOrderId(), otherStore.getStoreId())).isEmpty();
    }

    @Test
    @DisplayName("관리자 목록은 가게를 함께 읽어 가게 이름을 꺼낼 때 다시 조회하지 않는다")
    void adminPageFetchesStore() {
        save(childId, store, menu);
        save(otherChildId, otherStore, otherStoreMenu);
        entityManager.clear();

        List<ChildOrder> orders = childOrderRepository.findAll(PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "orderId")))
                .getContent();
        entityManager.clear();   // 영속성 컨텍스트를 닫은 뒤에도 가게 이름을 읽을 수 있어야 함께 읽은 것이다

        assertThat(orders).extracting(order -> order.getStore().getName()).containsExactly("샘플카페", "예시분식");
    }

    @Test
    @DisplayName("주문이 있는 가게를 알아본다 — 가게를 지울 때 거절하려고")
    void existsByStore() {
        save(childId, store, menu);

        assertThat(childOrderRepository.existsByStore_StoreId(store.getStoreId())).isTrue();
        assertThat(childOrderRepository.existsByStore_StoreId(otherStore.getStoreId())).isFalse();
    }
}
