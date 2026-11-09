package com.ktc4.backend.domain.order.service;

import com.ktc4.backend.domain.child.repository.ChildRepository;
import com.ktc4.backend.domain.member.entity.Member;
import com.ktc4.backend.domain.member.entity.OwnerInfo;
import com.ktc4.backend.domain.member.entity.StoreOwner;
import com.ktc4.backend.domain.member.enums.MemberRole;
import com.ktc4.backend.domain.member.repository.MemberRepository;
import com.ktc4.backend.domain.member.repository.StoreOwnerRepository;
import com.ktc4.backend.domain.member.service.StoreAccessService;
import com.ktc4.backend.domain.order.dto.ChildOrderCreateRequest;
import com.ktc4.backend.domain.order.enums.OrderStatus;
import com.ktc4.backend.domain.order.enums.ReceiveType;
import com.ktc4.backend.domain.order.repository.ChildOrderRepository;
import com.ktc4.backend.domain.store.entity.Store;
import com.ktc4.backend.domain.store.entity.StoreMenu;
import com.ktc4.backend.domain.store.enums.StoreStatus;
import com.ktc4.backend.domain.store.repository.StoreMenuRepository;
import com.ktc4.backend.domain.store.repository.StoreRepository;
import com.ktc4.backend.domain.store.service.StoreChangeHistoryService;
import com.ktc4.backend.domain.store.service.StoreService;
import com.ktc4.backend.global.error.CustomException;
import com.ktc4.backend.global.error.ErrorCode;
import com.ktc4.backend.global.security.AuthMember;
import com.ktc4.backend.support.PostgresContainerTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 점주 "조리 시작"과 아동 "취소"가 같은 주문에 동시에 와도 하나만 성공하는지 실제 Postgres 로 확인한다.
 *
 * <p>겹침을 운에 맡기지 않는다. 주문을 찾는 저장소 호출 뒤에 두 요청이 서로를 잠깐(최대 3초) 기다리게 한다 —
 * 행 잠금이 있으면 뒤 요청은 찾는 단계에서 막혀 기다림이 끝난 뒤 바뀐 상태를 보고 409 가 되고, 잠금이 빠지면 둘 다
 * "접수 대기"를 본 채 각자 바꿔 둘 다 성공한다(이 테스트가 실패해야 하는 경우). 테스트 트랜잭션은 끄고 직접 지운다.
 */
@Import({OrderService.class, StoreService.class, StoreChangeHistoryService.class, StoreAccessService.class,
        OrderConcurrencyTest.Wiring.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("OrderService 동시 상태 바꾸기")
class OrderConcurrencyTest extends PostgresContainerTest {

    private static final AuthMember ADMIN = new AuthMember(1L, MemberRole.ADMIN);

    /** 주문을 찾는 두 요청을 서로 잠깐 기다리게 한다. 꺼 두면 그냥 지나간다. */
    static final class Gate {
        private static volatile CyclicBarrier bothFound;

        static void afterFind() throws InterruptedException {
            CyclicBarrier barrier = bothFound;
            if (barrier == null) {
                return;
            }
            try {
                barrier.await(3, TimeUnit.SECONDS);
            } catch (TimeoutException | BrokenBarrierException e) {
                // 잠금 때문에 상대가 찾는 단계에서 막혀 있으면 여기로 온다 — 기다림을 끝내고 그대로 진행한다
            }
        }
    }

    @TestConfiguration
    static class Wiring {
        @Bean
        static BeanPostProcessor childOrderRepositoryGate() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) {
                    if (!(bean instanceof ChildOrderRepository real)) {
                        return bean;
                    }
                    return Proxy.newProxyInstance(ChildOrderRepository.class.getClassLoader(),
                            new Class<?>[]{ChildOrderRepository.class}, (proxy, method, args) -> {
                                Object result;
                                try {
                                    result = method.invoke(real, args);
                                } catch (InvocationTargetException e) {
                                    throw e.getCause();
                                }
                                if (method.getName().endsWith("ForUpdate")) {
                                    Gate.afterFind();
                                }
                                return result;
                            });
                }
            };
        }
    }

    @Autowired
    private OrderService orderService;

    @Autowired
    private StoreRepository storeRepository;

    @Autowired
    private StoreMenuRepository storeMenuRepository;

    @Autowired
    private ChildRepository childRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private StoreOwnerRepository storeOwnerRepository;

    private Long storeId;
    private Long menuId;
    private Long childId;
    private List<Long> memberIds = List.of();

    @BeforeEach
    void setUp() {
        Store store = storeRepository.saveAndFlush(Store.builder()
                .name("동시성분식").nameNormalized("동시성분식")
                .addressRoad("가상특별시 예시구 동시로 1").addressNormalized("가상특별시예시구동시로1")
                .status(StoreStatus.OPEN)
                .build());
        storeId = store.getStoreId();
        menuId = storeMenuRepository.saveAndFlush(StoreMenu.of(store, "후라이드 치킨", true)).getMenuId();
        // 주문은 점주가 연결된 가게에만 받는다
        LocalDateTime linkedAt = LocalDateTime.of(2026, 11, 1, 10, 0);
        Member admin = memberRepository.saveAndFlush(Member.admin("concurrency-admin@example.com", "hash"));
        Member owner = Member.ownerApplicant("concurrency-owner@example.com", "hash", OwnerInfo.builder()
                .bizNo("0000000000").storeName("동시성분식").representativeName("홍길동").phone("01000000000").build());
        owner.approve(linkedAt);
        owner = memberRepository.saveAndFlush(owner);
        memberIds = List.of(owner.getMemberId(), admin.getMemberId());
        storeOwnerRepository.saveAndFlush(StoreOwner.link(store, owner, admin, linkedAt));
        // 테스트 트랜잭션이 꺼져 있어 저장소의 쓰기 쿼리 대신 SQL 로 아동 행을 넣는다
        String hash = "f".repeat(64);
        jdbcTemplate.update("INSERT INTO child (card_hash, created_at, updated_at) VALUES (?, now(), now())", hash);
        childId = childRepository.findIdByCardHash(hash).orElseThrow();
    }

    @AfterEach
    void cleanUp() {
        Gate.bothFound = null;
        jdbcTemplate.update("DELETE FROM child_order WHERE store_id = ?", storeId);
        jdbcTemplate.update("DELETE FROM store_menu WHERE store_id = ?", storeId);
        jdbcTemplate.update("DELETE FROM store_owner WHERE store_id = ?", storeId);
        memberRepository.deleteAllById(memberIds);
        jdbcTemplate.update("DELETE FROM store WHERE store_id = ?", storeId);
        jdbcTemplate.update("DELETE FROM child WHERE child_id = ?", childId);
    }

    /** 성공했으면 null, 상태 충돌이면 그 에러 코드. 다른 예외는 그대로 던져 테스트를 깨뜨린다. */
    private static ErrorCode outcome(Future<?> future) throws Exception {
        try {
            future.get(30, TimeUnit.SECONDS);
            return null;
        } catch (ExecutionException e) {
            if (e.getCause() instanceof CustomException custom) {
                return custom.getErrorCode();
            }
            throw e;
        }
    }

    @Test
    @DisplayName("조리 시작과 아동 취소가 동시에 오면 하나만 성공하고, 남은 상태는 성공한 쪽의 결과다")
    void startCookingVersusCancel() throws Exception {
        Long orderId = orderService.create(childId,
                new ChildOrderCreateRequest(storeId, menuId, ReceiveType.TAKEOUT)).orderId();
        Gate.bothFound = new CyclicBarrier(2);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Callable<Object>> calls = List.of(
                    () -> {
                        start.await();
                        return orderService.startCooking(storeId, orderId, ADMIN);
                    },
                    () -> {
                        start.await();
                        return orderService.cancelByChild(childId, orderId);
                    });
            List<Future<Object>> futures = new ArrayList<>();
            for (Callable<Object> call : calls) {
                futures.add(pool.submit(call));
            }
            start.countDown();

            ErrorCode cookingResult = outcome(futures.get(0));
            ErrorCode cancelResult = outcome(futures.get(1));

            assertThat(List.of(String.valueOf(cookingResult), String.valueOf(cancelResult)))
                    .as("하나는 성공(null), 하나는 상태 충돌")
                    .containsExactlyInAnyOrder("null", ErrorCode.ORDER_STATUS_CONFLICT.name());
            String finalStatus = jdbcTemplate.queryForObject(
                    "SELECT status FROM child_order WHERE order_id = ?", String.class, orderId);
            assertThat(finalStatus).isEqualTo(cookingResult == null ? OrderStatus.COOKING.name() : OrderStatus.CANCELED.name());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("같은 아이의 주문이 동시에 여러 번 와도(연타·두 기기) 하나만 201 이고 나머지는 409 active-order-exists — 500 이 아니다")
    void concurrentCreate() throws Exception {
        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Object>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return orderService.create(childId, new ChildOrderCreateRequest(storeId, menuId, ReceiveType.TAKEOUT));
                }));
            }
            start.countDown();

            List<String> outcomes = new ArrayList<>();
            for (Future<Object> future : futures) {
                outcomes.add(String.valueOf(outcome(future)));
            }

            assertThat(outcomes).filteredOn("null"::equals).hasSize(1);
            assertThat(outcomes).filteredOn(ErrorCode.ACTIVE_ORDER_EXISTS.name()::equals).hasSize(threads - 1);
            Integer active = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM child_order WHERE child_id = ? AND status IN ('RECEIVED', 'COOKING')",
                    Integer.class, childId);
            assertThat(active).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }
}
