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
 * 파일 업로드({@code POST /api/files})를 같은 곳에서 되풀이해 보내면 거절한다. 성공·실패를 가리지 않고 센다.
 *
 * <p>업로드는 가입 신청 전에 부르므로 로그인 없이 열려 있다. 막지 않으면 파일을 끝없이 올려 디스크를 채울 수 있다.
 * 쌓인 파일 수에도 상한이 있지만({@code UploadFileService.MAX_PENDING_UPLOADS}) 그 상한이 차면 모두의 업로드가
 * 막힌다 — 한 곳에서 그 상한을 채우지 못하게 여기서 먼저 막는다.
 *
 * <p>두 가지를 센다.
 * <ul>
 *   <li>10분 동안 보낸 횟수 — 성공·실패를 가리지 않는다. 같은 곳에서 몰아 보내는 것을 막는다.</li>
 *   <li>하루 동안 올리는 데 성공한 수 — 쌓인 파일 상한을 채우는 것은 성공한 업로드뿐이다. 10분 한도만 있으면 한 곳에서
 *       몇 시간 만에 상한을 채울 수 있다. 가입 신청의 하루 성공 상한과 같은 방식이다.</li>
 * </ul>
 * 로그인 없이 받는 API 라 주소를 많이 가진 쪽을 완전히 막을 수는 없다. 이 두 한도와 짧은 보관 시간
 * ({@code upload.pending-ttl-hours})으로 상한을 채우는 데 드는 주소 수를 늘리고, 차더라도 오래 막히지 않게 한다.
 *
 * <p>보낸 쪽을 가리는 방법(IP·IPv6 /64·프록시 수)은 가입 신청과 같다 — {@link SignupRateLimitFilter} 참고.
 * 본문을 읽기 전에 거절해야 해서 필터에서 막는다. 본문 크기는 multipart 설정({@code spring.servlet.multipart})이 막는다.
 *
 * <p>{@code @Component} 로 만들지 않는다 — {@link JwtAuthenticationFilter} 와 같은 이유다.
 */
@Slf4j
@RequiredArgsConstructor
class UploadRateLimitFilter extends OncePerRequestFilter {

    // 해석된 경로로 비교한다 — 이유는 SignupRateLimitFilter.SIGNUP 과 같다
    private static final RequestMatcher UPLOAD = AntPathRequestMatcher.antMatcher(HttpMethod.POST, "/api/files");

    private final SignupRateLimiter attemptLimiter;
    private final SignupRateLimiter dailyUploadLimiter;
    private final int trustedProxies;
    private final ProblemResponseWriter problemWriter;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !UPLOAD.matches(request);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String ip = SignupRateLimitFilter.clientIp(request, trustedProxies);
        String key = SignupRateLimitFilter.bucketKey(ip);
        long retryAfterSeconds = dailyUploadLimiter.retryAfterIfExhausted(key);
        if (retryAfterSeconds == 0) {
            retryAfterSeconds = attemptLimiter.acquire(key);
        }
        if (retryAfterSeconds > 0) {
            log.warn("파일 업로드 횟수 초과 - ip={}, retryAfter={}s", SignupRateLimitFilter.maskIp(ip), retryAfterSeconds);
            response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfterSeconds));
            problemWriter.write(response, ErrorCode.TOO_MANY_REQUESTS);
            return;
        }
        chain.doFilter(request, response);

        if (response.getStatus() == HttpServletResponse.SC_CREATED) {
            dailyUploadLimiter.acquire(key);
        }
    }
}
