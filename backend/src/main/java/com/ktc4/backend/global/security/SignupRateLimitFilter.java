package com.ktc4.backend.global.security;

import com.ktc4.backend.global.error.ErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 점주 가입 신청을 같은 곳에서 되풀이해 보내면 거절한다. 두 가지를 센다.
 * <ul>
 *   <li><b>시도 횟수</b> — 성공·실패를 가리지 않는다. 형식이 틀려 거절된 요청도 서버는 파일까지 다 받은 뒤다.</li>
 *   <li><b>성공한 가입 수</b> — 검토되지 않은 신청을 한 곳에서 계속 쌓아 가입 전체를 닫아 버리는 것
 *       ({@code AuthService.MAX_PENDING_APPLICATIONS})을 막는다. 시도 횟수만으로는 몇 시간이면 그 상한이 찬다.</li>
 * </ul>
 *
 * <p>필터에서 막는 이유: 가입 신청은 사진·사업자등록증이 실린 큰 요청이다. 컨트롤러까지 가면 서버가 본문을 다 받은 뒤라,
 * 그 전에 — 본문을 읽기 전에 — 거절해야 한다. 이 필터 앞에서 본문을 읽는 필터가 없어야 한다(CSRF 필터는 꺼져 있다).
 *
 * <p>보낸 쪽은 IP 로 가린다. IP 는 횟수를 세는 동안 메모리에만 있고, 로그에는 끝자리를 가려 남긴다.
 * 어느 IP 로 볼지는 {@link #clientIp}, 어디까지를 한 곳으로 볼지는 {@link #bucketKey} 참고.
 *
 * <p>{@code @Component} 로 만들지 않는다 — {@link JwtAuthenticationFilter} 와 같은 이유다.
 */
@Slf4j
@RequiredArgsConstructor
class SignupRateLimitFilter extends OncePerRequestFilter {

    // 경로는 글자 그대로가 아니라 해석된 값으로 비교한다. getRequestURI() 는 보낸 그대로라서
    // "/api/auth/owners/sign%75p" 처럼 한 글자만 인코딩해 보내면 문자열 비교는 빗나가는데 컨트롤러에는 그대로 닿는다 —
    // 횟수를 세지 않고 가입을 되풀이할 수 있게 된다.
    private static final RequestMatcher SIGNUP =
            AntPathRequestMatcher.antMatcher(HttpMethod.POST, "/api/auth/owners/signup");

    private static final String FORWARDED_FOR = "X-Forwarded-For";
    // IPv4 는 점 네 칸, IPv6 는 콜론이 들어간 16진수. 이 모양이 아닌 값은 주소로 해석하지 않는다 — 이름으로 넘기면 DNS 조회가 나간다.
    private static final Pattern IPV4 = Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3}");
    private static final Pattern IPV6 = Pattern.compile("[0-9a-fA-F:.]*:[0-9a-fA-F:.]*");
    private static final int MAX_IP_LENGTH = 45;

    private final SignupRateLimiter attemptLimiter;
    private final SignupRateLimiter signupLimiter;
    private final int trustedProxies;
    private final ProblemResponseWriter problemWriter;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !SIGNUP.matches(request);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String ip = clientIp(request, trustedProxies);
        String key = bucketKey(ip);

        long retryAfterSeconds = signupLimiter.retryAfterIfExhausted(key);
        if (retryAfterSeconds == 0) {
            retryAfterSeconds = attemptLimiter.acquire(key);
        }
        if (retryAfterSeconds > 0) {
            log.warn("가입 신청 횟수 초과 - ip={}, retryAfter={}s", maskIp(ip), retryAfterSeconds);
            response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfterSeconds));
            problemWriter.write(response, ErrorCode.TOO_MANY_REQUESTS);
            return;
        }

        chain.doFilter(request, response);

        if (response.getStatus() == HttpServletResponse.SC_CREATED) {
            signupLimiter.acquire(key);
        }
    }

    /**
     * 요청을 보낸 쪽의 IP 를 정한다.
     *
     * <p>{@code getRemoteAddr()} 를 그대로 쓰지 않는다. 서버가 프록시 뒤에 있으면 그 값은 프록시의 주소이거나
     * ({@code X-Forwarded-For} 를 해석하도록 설정된 경우) 그 헤더의 <b>맨 왼쪽</b> 값인데, 맨 왼쪽은 보내는 쪽이
     * 마음대로 적을 수 있다 — 프록시가 헤더를 덮어쓰지 않고 뒤에 덧붙이는 설정이면 매번 다른 주소인 척할 수 있다.
     *
     * <p>그래서 헤더의 <b>오른쪽</b>에서 센다. 우리 프록시가 붙인 값은 오른쪽 끝에 있고, 그 값은 프록시가 실제로 본
     * 접속 주소라 보내는 쪽이 바꿀 수 없다. 프록시가 덧붙이든 덮어쓰든 결과가 같다.
     *
     * @param request        요청
     * @param trustedProxies 서버 앞에 있는 우리 프록시의 수. 0 이면 헤더를 보지 않고 실제 접속 주소를 쓴다
     * @return 보낸 쪽의 IP. 헤더의 칸이 프록시 수보다 적으면(프록시를 거치지 않은 요청) 실제 접속 주소
     */
    static String clientIp(HttpServletRequest request, int trustedProxies) {
        HttpServletRequest original = unwrap(request);
        if (trustedProxies > 0) {
            List<String> hops = new ArrayList<>();
            for (String line : Collections.list(original.getHeaders(FORWARDED_FOR))) {
                for (String hop : line.split(",")) {
                    hops.add(hop.strip());
                }
            }
            int index = hops.size() - trustedProxies;
            if (index >= 0 && !hops.get(index).isEmpty()) {
                return hops.get(index);
            }
        }
        return original.getRemoteAddr();
    }

    // 앞에서 요청을 감싼 필터들을 벗겨 서블릿 컨테이너가 준 원래 요청을 꺼낸다. X-Forwarded-For 를 해석하는 필터는
    // 그 헤더를 지우고 getRemoteAddr() 를 바꿔 놓는다 — 바뀌기 전의 값이 필요하다.
    private static HttpServletRequest unwrap(HttpServletRequest request) {
        HttpServletRequest current = request;
        while (current instanceof HttpServletRequestWrapper wrapper) {
            ServletRequest inner = wrapper.getRequest();
            if (!(inner instanceof HttpServletRequest innerHttp)) {
                break;
            }
            current = innerHttp;
        }
        return current;
    }

    /**
     * 어디까지를 "같은 곳"으로 셀지 정한다.
     *
     * <p>IPv4 는 주소 하나다. IPv6 는 앞 64비트(/64)다 — 가정이나 기기 하나가 보통 /64 대역을 통째로 받아
     * 그 안의 주소를 마음대로 바꿔 쓸 수 있다. 주소 하나씩 따로 세면 한 사람이 제한 없이 보낼 수 있고,
     * 기록을 가득 채워 다른 사람의 가입까지 막을 수 있다.
     *
     * @param ip 보낸 쪽의 IP
     * @return 횟수를 세는 단위. IP 모양이 아닌 값은 그대로(길면 잘라서) 쓴다
     */
    static String bucketKey(String ip) {
        if (ip == null) {
            return "unknown";
        }
        String trimmed = ip.length() > MAX_IP_LENGTH ? ip.substring(0, MAX_IP_LENGTH) : ip;
        if (IPV4.matcher(trimmed).matches()) {
            // 이미 주소 하나를 가리키는 표기다. InetAddress 로 넘기지 않는다 — "999.1.1.1" 처럼 주소가 아닌 값은 이름으로 보고 DNS 를 조회한다.
            return trimmed;
        }
        if (!IPV6.matcher(trimmed).matches()) {
            return trimmed;
        }
        try {
            // 콜론이 든 값은 IPv6 표기로만 해석한다(틀리면 조회 없이 예외). 줄여 쓴 표기(::)와 대소문자를 한 가지로 맞춘다.
            byte[] address = InetAddress.getByName(trimmed).getAddress();
            if (address.length != 16) {
                return (address[0] & 0xFF) + "." + (address[1] & 0xFF) + "." + (address[2] & 0xFF) + "."
                        + (address[3] & 0xFF);
            }
            StringBuilder prefix = new StringBuilder();
            for (int i = 0; i < 8; i += 2) {
                prefix.append(Integer.toHexString(((address[i] & 0xFF) << 8) | (address[i + 1] & 0xFF))).append(':');
            }
            return prefix.append(":/64").toString();
        } catch (UnknownHostException e) {
            return trimmed;
        }
    }

    // 같은 곳에서 되풀이해 들어왔는지만 알아볼 수 있게 끝자리를 가린다. IPv4 는 마지막 자리, IPv6 는 뒤쪽 절반.
    static String maskIp(String ip) {
        if (ip == null) {
            return "unknown";
        }
        int lastDot = ip.lastIndexOf('.');
        if (lastDot > 0 && ip.indexOf(':') < 0) {
            return ip.substring(0, lastDot) + ".***";
        }
        String[] groups = ip.split(":");
        if (groups.length > 4) {
            return String.join(":", groups[0], groups[1], groups[2], groups[3]) + ":***";
        }
        return "***";
    }
}
