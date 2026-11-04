package com.ktc4.backend.global.security;

import com.ktc4.backend.global.error.ErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 아동 급식카드 인증({@code POST /api/auth/children/card})을 되풀이해 부르면 거절한다.
 *
 * <p>실제 카드인지 확인하지 않아(시연용) 남의 카드 번호를 넣으면 그 아동의 토큰을 받는다 — 그 토큰으로 그 아동의 QR 을
 * 재발급해 옛 QR 을 무효로 만들 수 있다. 또 아무 16자리나 아동 한 명이 되므로 한 곳에서 아동 행을 끝없이 만들 수 있다.
 * 그래서 성공·실패를 가리지 않고 <b>부른 횟수</b>를 센다. 세는 것은 둘이다.
 * <ul>
 *   <li>같은 곳에서 10분 동안 — 몰아서 대입하는 것을 늦춘다</li>
 *   <li>같은 곳에서 하루 동안 — 10분마다 조금씩 부르는 것과, 아동 행이 끝없이 쌓이는 것을 막는다.
 *       "새 아동만" 세지 않는 이유: 새 카드와 등록된 카드가 같은 200 이라 요청 전에 세는 필터는 둘을 구분할 수 없다</li>
 * </ul>
 * 한 와이파이에 여러 아이가 있을 수 있어 가입 신청보다 넉넉히 둔다(설정으로 조절).
 *
 * <p>보낸 쪽을 가리는 방법(IP·IPv6 /64·프록시 수)은 가입 신청과 같다 — {@link SignupRateLimitFilter} 참고.
 * 프록시 수가 실제와 다르면 모든 아이가 프록시 주소 하나로 묶여 함께 막힌다.
 *
 * <p>{@code @Component} 로 만들지 않는다 — {@link JwtAuthenticationFilter} 와 같은 이유다.
 */
@Slf4j
@RequiredArgsConstructor
class ChildCardAuthRateLimitFilter extends OncePerRequestFilter {

    // 해석된 경로로 비교한다 — 이유는 SignupRateLimitFilter.SIGNUP 과 같다
    private static final RequestMatcher CARD_AUTH =
            AntPathRequestMatcher.antMatcher(HttpMethod.POST, "/api/auth/children/card");

    private final SignupRateLimiter attemptLimiter;
    private final SignupRateLimiter dailyLimiter;
    private final int trustedProxies;
    private final ProblemResponseWriter problemWriter;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !CARD_AUTH.matches(request);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String ip = SignupRateLimitFilter.clientIp(request, trustedProxies);
        String key = SignupRateLimitFilter.bucketKey(ip);

        long retryAfterSeconds = attemptLimiter.acquire(key);
        if (retryAfterSeconds == 0) {
            retryAfterSeconds = dailyLimiter.acquire(key);
        }
        if (retryAfterSeconds > 0) {
            log.warn("아동 카드 인증 횟수 초과 - ip={}, retryAfter={}s", SignupRateLimitFilter.maskIp(ip), retryAfterSeconds);
            response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfterSeconds));
            problemWriter.write(response, ErrorCode.TOO_MANY_REQUESTS);
            return;
        }
        chain.doFilter(request, response);
    }
}
