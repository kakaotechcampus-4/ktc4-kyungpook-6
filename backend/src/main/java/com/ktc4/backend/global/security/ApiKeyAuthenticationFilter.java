package com.ktc4.backend.global.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/**
 * 로그인하지 않는 서버(AI 서버)가 {@code X-API-KEY} 헤더로 보낸 키를 확인한다.
 *
 * <p>키가 맞으면 {@code ROLE_AI_SERVER} 권한만 준다. 이 권한으로 부를 수 있는 API 는
 * {@link SecurityConfig} 가 따로 정한다 — 키가 새도 관리자 API 까지 열리지 않게 하기 위해서다.
 *
 * <p>{@code @Component} 로 만들지 않는 이유는 {@link JwtAuthenticationFilter} 와 같다.
 */
@Slf4j
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-API-KEY";
    static final String AI_SERVER_ROLE = "AI_SERVER";
    private static final String AI_SERVER_PRINCIPAL = "ai-server";

    // null 이면 키 인증을 끈 상태다. 빈 키를 허용하면 헤더를 빈 값으로 보낸 요청이 통과해 버린다.
    private final byte[] expectedKey;

    ApiKeyAuthenticationFilter(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("INTERNAL_API_KEY 가 없어 서버 간 키 인증을 끕니다 — AI 서버가 조회 API 를 부를 수 없습니다");
            this.expectedKey = null;
            return;
        }
        this.expectedKey = apiKey.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String provided = request.getHeader(HEADER);
        if (provided != null && expectedKey != null) {
            // equals 는 다른 글자를 만나는 즉시 멈춰서, 응답 시간 차이로 키를 한 글자씩 알아낼 수 있다(타이밍 공격).
            if (MessageDigest.isEqual(expectedKey, provided.getBytes(StandardCharsets.UTF_8))) {
                SecurityContext context = SecurityContextHolder.createEmptyContext();
                context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                        AI_SERVER_PRINCIPAL, null, List.of(new SimpleGrantedAuthority("ROLE_" + AI_SERVER_ROLE))));
                SecurityContextHolder.setContext(context);
            } else {
                // 키 값은 남기지 않는다 — 로그가 새면 키가 새는 것과 같다.
                log.warn("서버 간 API 키가 맞지 않음 - uri={}", request.getRequestURI());
            }
        }
        chain.doFilter(request, response);
    }
}
