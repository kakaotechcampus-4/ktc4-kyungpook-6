package com.ktc4.backend.global.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * {@code Authorization: Bearer <토큰>} 헤더를 읽어 로그인 상태로 만든다.
 *
 * <p>토큰이 없거나 틀려도 여기서 막지 않고 그냥 넘긴다. 막을지는 뒤의 권한 규칙이 경로별로 정한다 —
 * 로그인 API 처럼 누구나 부를 수 있는 곳에 만료된 토큰을 달고 와도 통과해야 하기 때문이다.
 *
 * <p>{@code @Component} 로 만들지 않는다. 필터 빈은 스프링 부트가 서블릿 필터로도 따로 등록해 요청마다
 * 두 번 돌고, {@code @WebMvcTest} 가 필터 빈을 자동으로 불러와 컨트롤러 테스트가 기동부터 실패한다.
 * {@link SecurityConfig} 가 직접 만들어 보안 필터 체인에만 끼운다.
 */
@RequiredArgsConstructor
class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtProvider jwtProvider;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.startsWith(BEARER_PREFIX)) {
            jwtProvider.parse(header.substring(BEARER_PREFIX.length())).ifPresent(member -> {
                SecurityContext context = SecurityContextHolder.createEmptyContext();
                context.setAuthentication(
                        UsernamePasswordAuthenticationToken.authenticated(member, null, member.authorities()));
                SecurityContextHolder.setContext(context);
            });
        }
        chain.doFilter(request, response);
    }
}
