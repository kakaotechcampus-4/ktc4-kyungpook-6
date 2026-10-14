package com.ktc4.backend.global.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ktc4.backend.global.error.ErrorCode;
import jakarta.servlet.DispatcherType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.time.Clock;
import java.time.Duration;

/**
 * 어떤 API 를 누가 부를 수 있는지 정한다.
 *
 * <p>관리자는 {@code Authorization: Bearer <토큰>}, AI 서버는 {@code X-API-KEY} 로 들어온다.
 * 세션을 쓰지 않으므로(토큰 방식) CSRF 방어와 폼 로그인은 끈다 — CSRF 는 브라우저가 쿠키를 자동으로
 * 실어 보내는 세션 방식을 노리는 공격이라, 헤더에 직접 토큰을 싣는 방식에는 해당하지 않는다.
 *
 * <p>목록에 없는 경로는 관리자만 부를 수 있다. 새 API 를 추가하고 권한 설정을 잊어도 열리는 게 아니라
 * 막히게 하기 위해서다. 점주 API 가 생기면 여기에 경로를 추가한다.
 *
 * <p>{@code /api/admin/**} 와 {@code /api/children/**} 는 스위치와 무관하게 항상 관리자만 부를 수 있다.
 * 스위치는 이미 쓰이던 API 를 깨지 않으려고 둔 것인데, 이 경로들은 새로 만든 것이라 처음부터 막아도 깨지는 곳이 없다.
 * 아동 QR 발급({@code /api/children/**})은 다시 부르면 옛 QR 이 바로 무효가 되어, 열려 있으면 누구나 아무 아동의
 * QR 을 못 쓰게 만들 수 있다 — 아동 인증이 생기기 전까지 관리자만 부른다.
 *
 * <p>컨트롤러 테스트({@code @WebMvcTest})는 이 설정을 자동으로 불러오지 않는다 —
 * {@code @Import(SecurityConfig.class)} 로 가져와야 실제 규칙으로 검증된다.
 *
 * <p><b>{@code auth.enforce} 스위치</b> — 프론트 로그인 화면과 AI 서버 키가 준비되기 전에 머지해도 기존 화면이
 * 멈추지 않도록, 끄면 권한 규칙 없이 지금처럼 모두 통과시킨다(인증이 없던 머지 전과 같은 상태).
 * 꺼져 있어도 로그인 API 는 동작하므로 프론트·AI 가 실제 API 로 붙여 볼 수 있다.
 *
 * <p>⚠️ <b>임시 스위치다 — 2026-10-14 까지 제거한다.</b> 프론트 관리자 로그인 화면이 머지되고 AI 서버가
 * {@code X-API-KEY} 를 붙이면 서버 .env 의 {@code AUTH_ENFORCE=false} 를 지워 켜고, {@code enforce} 분기와
 * {@code auth.enforce} 설정을 지운다.
 * 기한 없는 임시 스위치는 영구가 된다. 꺼진 동안 새로 만드는 API 는 이 분기 위에(스위치와 무관하게) 규칙을 둔다.
 */
@Slf4j
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public JwtProvider jwtProvider(@Value("${auth.jwt.secret:}") String secret,
                                   @Value("${auth.jwt.validity-days:7}") long validityDays) {
        return new JwtProvider(secret, Duration.ofDays(validityDays), Clock.systemUTC());
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    // 가입 신청 횟수 제한의 설정값. 필터는 빈으로 만들지 않고 보안 필터 체인에만 끼운다(JwtAuthenticationFilter 와 같은 이유).
    @Value("${signup.rate-limit.max-attempts:10}")
    private int signupMaxAttempts;

    @Value("${signup.rate-limit.window-minutes:10}")
    private long signupWindowMinutes;

    @Value("${signup.rate-limit.max-signups-per-day:5}")
    private int signupMaxPerDay;

    // 설정이 빠지면 프록시가 없다고 본다(X-Forwarded-For 를 믿지 않는다). 프록시가 없는데 있다고 보면 보내는 쪽이 적은
    // 값을 믿어 제한이 조용히 뚫리고, 있는데 없다고 보면 모두가 한 주소로 묶여 바로 눈에 띈다 — 틀려도 드러나는 쪽을 고른다.
    @Value("${signup.rate-limit.trusted-proxies:0}")
    private int signupTrustedProxies;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   JwtProvider jwtProvider,
                                                   ObjectMapper objectMapper,
                                                   @Value("${auth.internal-api-key:}") String internalApiKey,
                                                   // 설정이 빠지면 켜진 쪽(안전한 쪽)으로 둔다. 끄는 것은 서버 .env 의 AUTH_ENFORCE=false 로만 한다.
                                                   @Value("${auth.enforce:true}") boolean enforce)
            throws Exception {
        ProblemResponseWriter problemWriter = new ProblemResponseWriter(objectMapper);
        if (!enforce) {
            log.warn("권한 검사가 꺼져 있습니다(AUTH_ENFORCE=false) — 로그인 없이 모든 API 를 부를 수 있습니다");
        }

        http
                .csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                // CORS 설정이 생기면 사전 요청(OPTIONS)이 인증에 막히지 않고 그 설정을 따르게 한다.
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> {
                    // 에러 응답을 그리러 내부적으로 다시 들어오는 요청까지 막으면 원래 상태코드 대신 401 이 나간다.
                    auth.dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                            .requestMatchers("/error").permitAll()
                            .requestMatchers(HttpMethod.GET, "/ping").permitAll()
                            .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                            .requestMatchers(HttpMethod.POST, "/api/auth/login", "/api/auth/owners/signup").permitAll()
                            // 가게 사진은 누구나 본다. 무엇이 공개 사진인지는 조회 쿼리가 정한다(FilePublicController 주석) —
                            // 사업자등록증은 이 경로로 나가지 않고 /api/admin/files/** 로만 나간다.
                            // 번호를 숫자로 좁히는 이유는 아래 /api/stores/{storeId} 규칙과 같다.
                            .requestMatchers(HttpMethod.GET, "/api/files/{fileId:\\d+}",
                                    "/api/stores/{storeId:\\d+}/images").permitAll()
                            // 토큰 주인을 알려주는 API 라 토큰 없이는 의미가 없다 — 스위치와 무관하게 막는다.
                            // 가입 상태 확인용 토큰이 부를 수 있는 API 는 이것 하나다. 승인 전 신청자가 자기 상태를 읽는다.
                            .requestMatchers(HttpMethod.GET, "/api/auth/me")
                            .hasAnyRole("ADMIN", "OWNER", AuthMember.SIGNUP_STATUS_ROLE)
                            // 점주 승인처럼 새로 만든 관리자 API — 스위치와 무관하게 막는다.
                            .requestMatchers("/api/admin/**").hasRole("ADMIN")
                            // 아동 QR 발급도 새로 만든 API 다. 재발급하면 옛 QR 이 무효가 되므로, 아동 인증이
                            // 생기기 전까지는 스위치와 무관하게 관리자만 부른다.
                            .requestMatchers("/api/children/**").hasRole("ADMIN")
                            // 관리자 조사(Job)도 새로 만든 API 다. AI 호출 비용이 드는 API 라 스위치와 무관하게 관리자만 부른다.
                            .requestMatchers("/api/jobs/**").hasRole("ADMIN")
                            // 조사 결과로 가게 정보를 바꾼다 — 스위치와 무관하게 관리자만
                            .requestMatchers("/api/tasks/**").hasRole("ADMIN")
                            // 점주 앱의 가게 상세 조회·운영 정보 저장도 새로 만든 API 다 — 스위치와 무관하게 점주·관리자만.
                            // "그 가게에 연결된 점주인지"는 서비스가 본다(체크인과 같은 방식).
                            // 가게 ID 를 숫자로 좁히는 이유: GET /api/stores/* 로 두면 GET /api/stores/nts-checks 와도 맞아,
                            // 아래 nts-checks 규칙보다 먼저 걸린다 — AI 서버 키는 막히고 점주에게는 국세청 대조 자료가 열린다.
                            .requestMatchers(HttpMethod.GET, "/api/stores/{storeId:\\d+}").hasAnyRole("OWNER", "ADMIN")
                            .requestMatchers(HttpMethod.PUT, "/api/stores/{storeId:\\d+}/operating-info")
                            .hasAnyRole("OWNER", "ADMIN");
                    // ⚠️ 임시 분기 — 2026-10-14 까지 제거 (클래스 주석 참고)
                    if (!enforce) {
                        auth.anyRequest().permitAll();
                        return;
                    }
                    // 점주가 아동 QR 을 찍어 방문을 기록한다(PR #50). 아래 /api/stores/** 관리자 규칙보다 먼저 와야 한다 —
                    // 규칙은 위에서부터 처음 맞는 것이 적용된다. "자기 가게인지" 검사는 점주↔가게 연결(다음 단계) 이후.
                    auth.requestMatchers(HttpMethod.POST, "/api/stores/*/check-ins").hasAnyRole("OWNER", "ADMIN")
                            .requestMatchers(HttpMethod.GET, "/api/stores/nts-checks")
                            .hasAnyRole("ADMIN", ApiKeyAuthenticationFilter.AI_SERVER_ROLE)
                            .requestMatchers("/api/stores/**", "/internal/**", "/test/**").hasRole("ADMIN")
                            .anyRequest().hasRole("ADMIN");
                })
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, e) ->
                                problemWriter.write(response, ErrorCode.UNAUTHORIZED))
                        .accessDeniedHandler((request, response, e) ->
                                problemWriter.write(response, ErrorCode.FORBIDDEN)))
                // 가입 신청은 로그인 없이 큰 파일을 받는다 — 같은 IP 에서 되풀이하면 본문을 읽기 전에 거절한다.
                // 이 필터 앞에서 본문을 읽는 필터가 없어야 한다 — CSRF 필터는 토큰을 찾으려고 본문을 읽는데, 위에서 꺼 두었다.
                .addFilterBefore(new SignupRateLimitFilter(
                                new SignupRateLimiter(signupMaxAttempts, Duration.ofMinutes(signupWindowMinutes),
                                        Clock.systemUTC()),
                                new SignupRateLimiter(signupMaxPerDay, Duration.ofDays(1), Clock.systemUTC()),
                                signupTrustedProxies, problemWriter),
                        UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(new ApiKeyAuthenticationFilter(internalApiKey),
                        UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(new JwtAuthenticationFilter(jwtProvider),
                        UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
