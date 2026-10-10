package com.ktc4.backend.global.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.time.Duration;
import java.util.List;

/**
 * 다른 도메인의 프론트가 브라우저에서 API 를 부를 수 있게 허용할 출처(Origin)를 정한다.
 *
 * <p>{@code SecurityConfig} 의 {@code .cors(Customizer.withDefaults())} 가 이 빈을 찾아 쓴다 — 빈이 없으면
 * 사전 요청(OPTIONS)이 모두 403 으로 막힌다.
 *
 * <p>허용 출처는 {@code cors.allowed-origins}(환경변수 {@code CORS_ALLOWED_ORIGINS}, 쉼표 구분)로 받는다.
 * 배포 환경마다 프론트 주소가 달라서 코드에 박지 않는다. 토큰은 쿠키가 아니라 {@code Authorization} 헤더로
 * 싣기 때문에 credentials 는 허용하지 않는다.
 */
@Configuration
public class CorsConfig {

    @Bean
    public CorsConfigurationSource corsConfigurationSource(
            @Value("${cors.allowed-origins:}") List<String> allowedOrigins) {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(allowedOrigins.stream().map(String::trim).filter(o -> !o.isEmpty()).toList());
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of(HttpHeaders.AUTHORIZATION, HttpHeaders.CONTENT_TYPE, "X-API-KEY"));
        // 사전 요청 결과를 브라우저가 1시간 기억해, 매 요청마다 OPTIONS 를 다시 보내지 않게 한다.
        config.setMaxAge(Duration.ofHours(1));

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
