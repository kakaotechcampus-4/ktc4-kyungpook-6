package com.ktc4.backend.global.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 같은 곳에서 가입 신청을 되풀이하는 횟수를 센다. 정해진 시간 동안 정해진 횟수까지만 허용한다.
 *
 * <p>가입 신청은 로그인 없이 부르고 한 번에 수십 MB 의 파일을 받는다. 제한이 없으면 누구나 되풀이해 보낼 수 있다.
 *
 * <p>횟수는 서버 메모리에만 둔다. 서버가 한 대라 충분하고, 다시 띄우면 초기화된다. 서버가 여러 대가 되면
 * 서버마다 따로 세게 되므로 그때는 공용 저장소로 옮겨야 한다.
 *
 * <p>성공·실패를 가리지 않고 시도한 횟수를 센다 — 형식이 틀려 거절된 요청도 서버는 파일까지 다 받은 뒤다.
 */
class SignupRateLimiter {

    /** 기록이 이만큼 쌓이면 시간이 지난 기록을 치운다. 주소를 바꿔 가며 보내도 메모리가 끝없이 늘지 않게. */
    private static final int CLEANUP_THRESHOLD = 10_000;

    private final int maxAttempts;
    private final Duration window;
    private final Clock clock;
    // 기록이 가득 찼을 때 처음 보는 값을 거절할지(기본), 세지 않고 통과시킬지
    private final boolean passWhenFull;
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    /**
     * @param maxAttempts 한 구간에 허용하는 횟수
     * @param window      구간의 길이
     * @param clock       현재 시각 기준. 테스트에서 시간이 지난 것을 재현하려고 주입받는다
     */
    SignupRateLimiter(int maxAttempts, Duration window, Clock clock) {
        this(maxAttempts, window, clock, false);
    }

    private SignupRateLimiter(int maxAttempts, Duration window, Clock clock, boolean passWhenFull) {
        this.maxAttempts = maxAttempts;
        this.window = window;
        this.clock = clock;
        this.passWhenFull = passWhenFull;
    }

    /**
     * 기록이 가득 차면 처음 보는 값을 <b>세지 않고 통과시키는</b> 카운터. 다른 한도가 이미 비용을 묶고 있어, 이 카운터가
     * 막히면 정상 사용자까지 모두 막히는 쪽이 더 나쁠 때 쓴다 — 예: 사업자등록증 읽기의 파일 ID 별 횟수. 지어낸 파일 ID 로
     * 기록을 채우면 그동안 처음 읽는 점주가 모두 막힌다. 그때 비용은 같은 곳 한도와 서버 전체 상한이 막는다.
     */
    static SignupRateLimiter passingWhenFull(int maxAttempts, Duration window, Clock clock) {
        return new SignupRateLimiter(maxAttempts, window, clock, true);
    }

    /**
     * 시도 한 번을 세고, 허용되는지 답한다.
     *
     * @param key 보낸 쪽을 가리는 값 (IP)
     * @return 허용이면 0. 거절이면 다시 시도할 수 있을 때까지 남은 초(1 이상)
     */
    long acquire(String key) {
        Instant now = clock.instant();
        if (windows.size() > CLEANUP_THRESHOLD) {
            windows.values().removeIf(w -> !now.isBefore(w.start().plus(window)));
            // 치우고도 가득 차 있으면 한 구간 안에 서로 다른 주소가 그만큼 몰린 것이다. 처음 보는 주소는 받지 않는다 —
            // 기록을 끝없이 늘려 메모리를 채우는 것보다, 그동안 새 가입을 잠시 거절하는 쪽이 낫다.
            if (windows.size() > CLEANUP_THRESHOLD && !windows.containsKey(key)) {
                return passWhenFull ? 0 : window.toSeconds();
            }
        }
        Window current = windows.compute(key, (k, existing) -> {
            if (existing == null || !now.isBefore(existing.start().plus(window))) {
                return new Window(now, 1);
            }
            // 넘친 뒤에도 계속 세면 값이 끝없이 커진다 — 한도 바로 위에서 멈춘다
            return new Window(existing.start(), Math.min(existing.count() + 1, maxAttempts + 1));
        });
        if (current.count() <= maxAttempts) {
            return 0;
        }
        long remaining = Duration.between(now, current.start().plus(window)).toSeconds();
        return Math.max(remaining, 1);
    }

    /**
     * 세지 않고, 이미 한도를 다 썼는지만 답한다. 성공한 뒤에만 {@link #acquire} 로 세는 경우에 쓴다.
     *
     * @param key 보낸 쪽을 가리는 값
     * @return 아직 여유가 있으면 0. 다 썼으면 다시 시도할 수 있을 때까지 남은 초(1 이상)
     */
    long retryAfterIfExhausted(String key) {
        Instant now = clock.instant();
        Window current = windows.get(key);
        if (current == null || !now.isBefore(current.start().plus(window)) || current.count() < maxAttempts) {
            return 0;
        }
        return Math.max(Duration.between(now, current.start().plus(window)).toSeconds(), 1);
    }

    private record Window(Instant start, int count) {
    }
}
