package com.ktc4.backend.domain.child.service;

import com.ktc4.backend.domain.child.repository.ChildRepository;
import com.ktc4.backend.global.security.JwtProvider;
import com.ktc4.backend.support.PostgresContainerTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 같은 카드로 동시에 처음 인증해도 아동이 하나만 생기고, 모두 같은 아동의 토큰을 받는지 실제 Postgres 로 확인한다.
 *
 * <p>가짜 저장소로는 못 보는 문제다 — 둘이 함께 "없음"을 보고 넣으면 하나는 unique 제약에 걸리는데, Postgres 는 그 뒤
 * 같은 트랜잭션의 조회를 거절한다. 그래서 테스트 트랜잭션을 끄고({@code NOT_SUPPORTED}) 서비스가 연 트랜잭션끼리 겹치게 한다.
 *
 * <p>겹침을 운에 맡기지 않는다. 실제 저장소를 그대로 부르는 얇은 프록시({@link RaceGate})로 감싸, 모든 스레드가 첫 조회에서
 * "없음"을 본 뒤에야 넣기로 넘어가게 붙잡는다. 그래서 매번 모든 스레드가 넣기를 시도한다.
 * 롤백이 없으므로 이 테스트가 넣은 행은 직접 지운다.
 */
@Import({ChildAuthService.class, CardNumberHasher.class, ChildAuthConcurrencyTest.Wiring.class})
@TestPropertySource(properties = "child.card-hash-secret=" + ChildAuthConcurrencyTest.SECRET)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("ChildAuthService 동시 첫 인증")
class ChildAuthConcurrencyTest extends PostgresContainerTest {

    static final String SECRET = "test-only-card-hash-secret-at-least-32-bytes";
    private static final int THREADS = 8;
    private static final List<String> CARDS = List.of(
            "9000000000000001", "9000000000000002", "9000000000000003", "9000000000000004", "9000000000000005");

    /**
     * 저장소 호출을 지켜보고, 켜 두면 각 스레드의 첫 조회 뒤에 모든 스레드가 모일 때까지 기다리게 한다.
     * 꺼 두면(기본) 그냥 지나간다.
     */
    static final class RaceGate {
        private static volatile CyclicBarrier allSawAbsent;
        private static final Set<Long> waited = ConcurrentHashMap.newKeySet();
        private static final AtomicInteger insertCalls = new AtomicInteger();
        private static final AtomicInteger inserted = new AtomicInteger();

        static void arm(int parties) {
            waited.clear();
            insertCalls.set(0);
            inserted.set(0);
            allSawAbsent = new CyclicBarrier(parties);
        }

        static void disarm() {
            allSawAbsent = null;
        }

        static void afterFind() throws Exception {
            CyclicBarrier barrier = allSawAbsent;
            if (barrier != null && waited.add(Thread.currentThread().threadId())) {
                barrier.await(30, TimeUnit.SECONDS);
            }
        }

        static void afterInsert(int rows) {
            insertCalls.incrementAndGet();
            inserted.addAndGet(rows);
        }
    }

    @TestConfiguration
    static class Wiring {
        @Bean
        JwtProvider jwtProvider() {
            return new JwtProvider("test-only-jwt-secret-at-least-32-bytes-long", Duration.ofDays(7), Clock.systemUTC());
        }

        // 실제 저장소를 감싼다 — 메서드는 실제 것을 그대로 부르고, 부른 뒤에 RaceGate 에 알린다
        @Bean
        static BeanPostProcessor childRepositoryRaceGate() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) {
                    if (!(bean instanceof ChildRepository real)) {
                        return bean;
                    }
                    return Proxy.newProxyInstance(ChildRepository.class.getClassLoader(),
                            new Class<?>[]{ChildRepository.class}, (proxy, method, args) -> {
                                Object result;
                                try {
                                    result = method.invoke(real, args);
                                } catch (InvocationTargetException e) {
                                    throw e.getCause();
                                }
                                if (method.getName().equals("findIdByCardHash")) {
                                    RaceGate.afterFind();
                                } else if (method.getName().equals("insertIfAbsent")) {
                                    RaceGate.afterInsert((int) result);
                                }
                                return result;
                            });
                }
            };
        }
    }

    @Autowired
    private ChildAuthService childAuthService;

    @Autowired
    private CardNumberHasher cardNumberHasher;

    @Autowired
    private JwtProvider jwtProvider;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void cleanUp() {
        RaceGate.disarm();
        for (String card : CARDS) {
            jdbcTemplate.update("DELETE FROM child WHERE card_hash = ?", cardNumberHasher.hash(card));
        }
    }

    private Long childIdOf(String accessToken) {
        return jwtProvider.parse(accessToken).orElseThrow().childId();
    }

    @Test
    @DisplayName("같은 카드로 여러 요청이 동시에 와도 모두 성공하고, 같은 아동 하나만 생긴다 — 모두가 \"없음\"을 본 뒤 넣는 경우")
    void concurrentFirstAuthCreatesOneChild() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        try {
            for (String card : CARDS) {
                RaceGate.arm(THREADS);
                CountDownLatch start = new CountDownLatch(1);
                List<Future<String>> results = new ArrayList<>();
                for (int i = 0; i < THREADS; i++) {
                    results.add(pool.submit(() -> {
                        start.await();
                        return childAuthService.authenticate(card).accessToken();
                    }));
                }
                start.countDown();

                Set<Long> childIds = new HashSet<>();
                for (Future<String> result : results) {
                    // 하나라도 예외로 끝나면 get() 이 그 예외를 던져 테스트가 실패한다
                    childIds.add(childIdOf(result.get(30, TimeUnit.SECONDS)));
                }

                Integer rows = jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM child WHERE card_hash = ?", Integer.class, cardNumberHasher.hash(card));
                assertThat(RaceGate.insertCalls.get()).as(card + " 넣기 시도").isEqualTo(THREADS);   // 경합이 실제로 일어났다
                assertThat(RaceGate.inserted.get()).as(card + " 실제로 넣은 수").isEqualTo(1);
                assertThat(childIds).as(card).hasSize(1);
                assertThat(rows).as(card).isEqualTo(1);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("다른 카드끼리 동시에 와도 각자 다른 아동이 된다")
    void concurrentDifferentCards() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(CARDS.size());
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<String>> results = new ArrayList<>();
            for (String card : CARDS) {
                results.add(pool.submit(() -> {
                    start.await();
                    return childAuthService.authenticate(card).accessToken();
                }));
            }
            start.countDown();

            Set<Long> childIds = results.stream().map(result -> {
                try {
                    return childIdOf(result.get(30, TimeUnit.SECONDS));
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }).collect(Collectors.toSet());
            assertThat(childIds).hasSize(CARDS.size());
        } finally {
            pool.shutdownNow();
        }
    }
}
