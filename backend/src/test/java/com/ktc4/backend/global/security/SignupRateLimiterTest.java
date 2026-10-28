package com.ktc4.backend.global.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

// IP 는 문서용으로 예약된 대역(RFC 5737)의 값이다. 실제 기기에 배정되지 않는다.
@DisplayName("SignupRateLimiter")
class SignupRateLimiterTest {

    private static final String IP = "203.0.113.10";
    private static final String OTHER_IP = "198.51.100.20";
    private static final Duration WINDOW = Duration.ofMinutes(10);

    /** 테스트가 시간을 직접 넘기는 시계. */
    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-10-12T03:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }

    private final MutableClock clock = new MutableClock();
    private final SignupRateLimiter limiter = new SignupRateLimiter(3, WINDOW, clock);

    @Test
    @DisplayName("한도까지는 허용하고, 넘으면 다시 시도할 수 있을 때까지 남은 초를 돌려준다")
    void allowsUpToLimit() {
        assertThat(limiter.acquire(IP)).isZero();
        assertThat(limiter.acquire(IP)).isZero();
        assertThat(limiter.acquire(IP)).isZero();

        assertThat(limiter.acquire(IP)).isEqualTo(WINDOW.toSeconds());
    }

    @Test
    @DisplayName("남은 시간은 구간이 시작된 때부터 센다 — 거절된 뒤 또 보내도 기다릴 시간이 늘지 않는다")
    void retryAfterCountsFromWindowStart() {
        for (int i = 0; i < 3; i++) {
            limiter.acquire(IP);
        }
        clock.advance(Duration.ofMinutes(4));

        assertThat(limiter.acquire(IP)).isEqualTo(Duration.ofMinutes(6).toSeconds());
        assertThat(limiter.acquire(IP)).isEqualTo(Duration.ofMinutes(6).toSeconds());
    }

    @Test
    @DisplayName("구간이 지나면 다시 처음부터 센다")
    void resetsAfterWindow() {
        for (int i = 0; i < 4; i++) {
            limiter.acquire(IP);
        }
        clock.advance(WINDOW.minusSeconds(1));
        assertThat(limiter.acquire(IP)).isEqualTo(1);

        clock.advance(Duration.ofSeconds(1));
        assertThat(limiter.acquire(IP)).isZero();
        assertThat(limiter.acquire(IP)).isZero();
        assertThat(limiter.acquire(IP)).isZero();
        assertThat(limiter.acquire(IP)).isPositive();
    }

    @Test
    @DisplayName("IP 마다 따로 센다 — 한 곳이 막혀도 다른 곳은 가입할 수 있다")
    void countsPerKey() {
        for (int i = 0; i < 4; i++) {
            limiter.acquire(IP);
        }

        assertThat(limiter.acquire(IP)).isPositive();
        assertThat(limiter.acquire(OTHER_IP)).isZero();
    }

    @Test
    @DisplayName("세지 않고 한도를 다 썼는지만 물을 수 있다 — 물어보는 것만으로는 횟수가 늘지 않는다")
    void asksWithoutCounting() {
        for (int i = 0; i < 10; i++) {
            assertThat(limiter.retryAfterIfExhausted(IP)).isZero();
        }
        limiter.acquire(IP);
        limiter.acquire(IP);
        assertThat(limiter.retryAfterIfExhausted(IP)).isZero();   // 한도 3 중 2 를 썼다

        limiter.acquire(IP);
        assertThat(limiter.retryAfterIfExhausted(IP)).isEqualTo(WINDOW.toSeconds());   // 다 썼다
        assertThat(limiter.retryAfterIfExhausted(OTHER_IP)).isZero();

        clock.advance(WINDOW);
        assertThat(limiter.retryAfterIfExhausted(IP)).isZero();
    }

    @Test
    @DisplayName("한 구간에 서로 다른 주소가 너무 많이 몰리면 처음 보는 주소는 받지 않는다 — 기록이 끝없이 늘지 않게. 구간이 지나면 다시 받는다")
    void refusesNewKeysWhenFull() {
        SignupRateLimiter roomy = new SignupRateLimiter(3, WINDOW, clock);
        for (int i = 0; i <= 10_000; i++) {
            assertThat(roomy.acquire("key-" + i)).isZero();
        }

        assertThat(roomy.acquire("one-more")).isPositive();
        // 이미 세고 있던 주소는 계속 자기 한도대로 센다
        assertThat(roomy.acquire("key-0")).isZero();

        clock.advance(WINDOW);
        assertThat(roomy.acquire("one-more")).isZero();
    }

    @Test
    @DisplayName("가득 차면 통과시키는 카운터는 처음 보는 값을 세지 않고 받는다 — 이미 세던 값은 계속 자기 한도대로 센다")
    void passingWhenFullLetsNewKeysThrough() {
        SignupRateLimiter passing = SignupRateLimiter.passingWhenFull(3, WINDOW, clock);
        for (int i = 0; i <= 10_000; i++) {
            assertThat(passing.acquire("key-" + i)).isZero();
        }

        for (int i = 0; i < 5; i++) {
            assertThat(passing.acquire("one-more")).isZero();   // 기록에 남지 않으니 몇 번이든 통과
        }
        passing.acquire("key-0");
        passing.acquire("key-0");
        assertThat(passing.acquire("key-0")).isPositive();   // 세던 값은 그대로 막힌다
    }

    @Test
    @DisplayName("수없이 되풀이해도 구간이 지나면 풀린다 — 넘친 횟수가 다음 구간으로 이어지지 않는다")
    void floodDoesNotCarryOver() {
        for (int i = 0; i < 1_000; i++) {
            limiter.acquire(IP);
        }
        clock.advance(WINDOW);

        assertThat(limiter.acquire(IP)).isZero();
    }
}
