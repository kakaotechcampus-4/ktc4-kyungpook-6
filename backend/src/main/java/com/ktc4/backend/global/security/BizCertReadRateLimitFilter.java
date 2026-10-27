package com.ktc4.backend.global.security;

import com.ktc4.backend.domain.file.FileIdFormat;
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
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.regex.Pattern;

/**
 * 사업자등록증 읽기({@code POST /api/files/{fileId}/biz-cert-extraction})를 되풀이해 부르면 거절한다.
 *
 * <p>로그인 없이 부르고, 부를 때마다 모델 비용이 든다. 그래서 성공·실패를 가리지 않고 <b>부른 횟수</b>를 센다 —
 * 모델이 실패해도 비용은 이미 나갔다. 세는 것은 셋이다.
 * <ul>
 *   <li>같은 곳에서 10분 동안 — 몰아 부르는 것을 막는다</li>
 *   <li>같은 곳에서 하루 동안 — 10분마다 조금씩 부르는 것을 막는다</li>
 *   <li>같은 파일 ID 로 — 읽은 값을 서버에 남기지 않아 같은 파일을 다시 부르면 모델도 다시 불린다.
 *       파일이 남아 있는 동안만 센다</li>
 * </ul>
 * 같은 곳에서 막힌 요청은 파일 ID 로는 세지 않는다 — 한 곳이 남의 파일 ID 몫을 깎지 못하게. 반대로 파일 한도에 막힌 요청도
 * 같은 곳 횟수는 이미 깎는다 — 되풀이하는 쪽의 비용을 늘리는 방향이라 그대로 둔다.
 *
 * <p>파일 ID 별 기록이 가득 차면 그 카운터만 통과시킨다({@link SignupRateLimiter#passingWhenFull}). 지어낸 파일 ID 로 기록을
 * 채워 처음 읽는 점주를 모두 막지 못하게 하려는 것이다. 그동안 비용은 같은 곳 한도와 서버 전체 상한(읽기 구현 쪽)이 막는다.
 *
 * <p>보안 규칙이 열어 둔 모양(파일 ID 가 소문자 UUID)의 경로만 센다. 모양이 틀린 경로는 관리자 전용 규칙에 막혀
 * 모델까지 가지 않으니 셀 이유가 없다.
 *
 * <p>보낸 쪽을 가리는 방법(IP·IPv6 /64·프록시 수)은 가입 신청과 같다 — {@link SignupRateLimitFilter} 참고.
 *
 * <p>{@code @Component} 로 만들지 않는다 — {@link JwtAuthenticationFilter} 와 같은 이유다.
 */
@Slf4j
@RequiredArgsConstructor
class BizCertReadRateLimitFilter extends OncePerRequestFilter {

    // 해석된 경로로 비교한다 — 이유는 SignupRateLimitFilter.SIGNUP 과 같다
    private static final AntPathRequestMatcher READ =
            AntPathRequestMatcher.antMatcher(HttpMethod.POST, "/api/files/{fileId}/biz-cert-extraction");
    private static final Pattern FILE_ID = Pattern.compile(FileIdFormat.PATTERN);

    private final SignupRateLimiter attemptLimiter;
    private final SignupRateLimiter dailyLimiter;
    private final SignupRateLimiter perFileLimiter;
    private final int trustedProxies;
    private final ProblemResponseWriter problemWriter;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String fileId = READ.matcher(request).getVariables().get("fileId");
        return fileId == null || !FILE_ID.matcher(fileId).matches();
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
        if (retryAfterSeconds == 0) {
            retryAfterSeconds = perFileLimiter.acquire(READ.matcher(request).getVariables().get("fileId"));
        }
        if (retryAfterSeconds > 0) {
            // 파일 ID 는 남기지 않는다 — 그 값을 아는 것이 곧 그 파일을 쓸 자격이다
            log.warn("사업자등록증 읽기 횟수 초과 - ip={}, retryAfter={}s", SignupRateLimitFilter.maskIp(ip), retryAfterSeconds);
            response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfterSeconds));
            problemWriter.write(response, ErrorCode.TOO_MANY_REQUESTS);
            return;
        }
        chain.doFilter(request, response);
    }
}
