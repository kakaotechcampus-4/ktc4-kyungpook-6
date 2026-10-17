package com.ktc4.backend.global.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ktc4.backend.domain.file.FileIdFormat;
import com.ktc4.backend.global.error.ErrorCode;
import jakarta.servlet.DispatcherType;
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
 * <p>아동 QR 발급({@code /api/children/**})은 다시 부르면 옛 QR 이 바로 무효가 되어, 열려 있으면 누구나 아무 아동의
 * QR 을 못 쓰게 만들 수 있다 — 아동 인증이 생기기 전까지 관리자만 부른다.
 *
 * <p>권한 검사는 끌 수 없다. 로그인 기능을 넣을 때 프론트·AI 가 준비되기 전이라 끌 수 있는 임시 스위치
 * ({@code AUTH_ENFORCE})를 두었고, 준비가 끝나 PROMPT-146 에서 지웠다. 다시 만들지 못하게
 * {@code SecurityConfigAlwaysEnforcedTest} 가 고정한다.
 *
 * <p>컨트롤러 테스트({@code @WebMvcTest})는 이 설정을 자동으로 불러오지 않는다 —
 * {@code @Import(SecurityConfig.class)} 로 가져와야 실제 규칙으로 검증된다.
 */
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

    @Value("${signup.max-body-bytes:65536}")
    private long signupMaxBodyBytes;

    // 파일 업로드 횟수 제한. 가입 한 건에 메뉴 사진이 많으면 수십 장이라 가입 신청보다 넉넉하다
    @Value("${upload.rate-limit.max-attempts:60}")
    private int uploadMaxAttempts;

    @Value("${upload.rate-limit.window-minutes:10}")
    private long uploadWindowMinutes;

    // 같은 곳에서 하루에 올릴 수 있는 파일 수(성공한 것만). 가입 한 건에 파일이 많아야 52개(메뉴 50 + 대표 사진 + 사업자등록증)다
    @Value("${upload.rate-limit.max-uploads-per-day:150}")
    private int uploadMaxPerDay;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   JwtProvider jwtProvider,
                                                   ObjectMapper objectMapper,
                                                   @Value("${auth.internal-api-key:}") String internalApiKey)
            throws Exception {
        ProblemResponseWriter problemWriter = new ProblemResponseWriter(objectMapper);

        http
                .csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                // CORS 설정이 생기면 사전 요청(OPTIONS)이 인증에 막히지 않고 그 설정을 따르게 한다.
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // 규칙은 위에서부터 처음 맞는 것이 적용된다 — 좁은 경로를 넓은 경로(/api/stores/** 등)보다 위에 둔다.
                .authorizeHttpRequests(auth -> auth
                        // 에러 응답을 그리러 내부적으로 다시 들어오는 요청까지 막으면 원래 상태코드 대신 401 이 나간다.
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers("/error").permitAll()
                        .requestMatchers(HttpMethod.GET, "/ping").permitAll()
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/auth/login", "/api/auth/owners/signup").permitAll()
                        // 가입 신청에 붙일 파일. 가입 전이라 계정이 없다 — 횟수는 UploadRateLimitFilter 가 막는다
                        .requestMatchers(HttpMethod.POST, "/api/files").permitAll()
                        // 가게 사진은 관리자와 그 가게에 연결된 점주만 본다(StoreImageController 주석). 어느 가게의 점주인지는
                        // 서비스가 확인한다. 사업자등록증은 이 경로로 나가지 않고 /api/admin/files/** 로만 나간다.
                        // 가게 번호를 숫자로 좁히는 이유는 아래 /api/stores/{storeId} 규칙과 같다.
                        .requestMatchers(HttpMethod.GET, "/api/files/{fileId:" + FileIdFormat.PATH_REGEX + "}",
                                "/api/stores/{storeId:\\d+}/images").hasAnyRole("OWNER", "ADMIN")
                        // 토큰 주인을 알려주는 API 다. 가입 상태 확인용 토큰이 부를 수 있는 API 는 이것 하나다 —
                        // 승인 전 신청자가 자기 상태를 읽는다.
                        .requestMatchers(HttpMethod.GET, "/api/auth/me")
                        .hasAnyRole("ADMIN", "OWNER", AuthMember.SIGNUP_STATUS_ROLE)
                        // 점주 가입 승인 같은 관리자 API
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        // 아동 QR 발급 — 재발급하면 옛 QR 이 무효가 되므로 아동 인증이 생기기 전까지 관리자만(클래스 주석)
                        .requestMatchers("/api/children/**").hasRole("ADMIN")
                        // 관리자 조사(Job) — AI 호출 비용이 드는 API 다
                        .requestMatchers("/api/jobs/**").hasRole("ADMIN")
                        // 조사 결과로 가게 정보를 바꾼다
                        .requestMatchers("/api/tasks/**").hasRole("ADMIN")
                        // 점주 앱의 가게 상세 조회·운영 정보 저장 — 점주·관리자. "그 가게에 연결된 점주인지"는 서비스가 본다
                        // (체크인과 같은 방식). 가게 ID 를 숫자로 좁히는 이유: GET /api/stores/* 로 두면
                        // GET /api/stores/nts-checks 와도 맞아 아래 nts-checks 규칙보다 먼저 걸린다 — AI 서버 키는 막히고
                        // 점주에게는 국세청 대조 자료가 열린다.
                        .requestMatchers(HttpMethod.GET, "/api/stores/{storeId:\\d+}").hasAnyRole("OWNER", "ADMIN")
                        .requestMatchers(HttpMethod.PUT, "/api/stores/{storeId:\\d+}/operating-info")
                        .hasAnyRole("OWNER", "ADMIN")
                        // 점주가 아동 QR 을 찍어 방문을 기록한다(PR #50). "그 가게에 연결된 점주인지"는 서비스가 본다
                        .requestMatchers(HttpMethod.POST, "/api/stores/*/check-ins").hasAnyRole("OWNER", "ADMIN")
                        // AI 서버는 이 하나만 부를 수 있다 — 키가 새도 관리자 권한까지 열리지 않게
                        .requestMatchers(HttpMethod.GET, "/api/stores/nts-checks")
                        .hasAnyRole("ADMIN", ApiKeyAuthenticationFilter.AI_SERVER_ROLE)
                        .requestMatchers("/api/stores/**", "/internal/**", "/test/**").hasRole("ADMIN")
                        // 목록에 없는 경로는 관리자만 — 새 API 의 권한 설정을 잊어도 열리지 않고 막히게
                        .anyRequest().hasRole("ADMIN"))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, e) ->
                                problemWriter.write(response, ErrorCode.UNAUTHORIZED))
                        .accessDeniedHandler((request, response, e) ->
                                problemWriter.write(response, ErrorCode.FORBIDDEN)))
                // 가입 신청은 로그인 없이 받는다 — 같은 IP 에서 되풀이하거나 본문이 너무 크면 본문을 읽기 전에 거절한다.
                // 이 필터 앞에서 본문을 읽는 필터가 없어야 한다 — CSRF 필터는 토큰을 찾으려고 본문을 읽는데, 위에서 꺼 두었다.
                .addFilterBefore(new SignupRateLimitFilter(
                                new SignupRateLimiter(signupMaxAttempts, Duration.ofMinutes(signupWindowMinutes),
                                        Clock.systemUTC()),
                                new SignupRateLimiter(signupMaxPerDay, Duration.ofDays(1), Clock.systemUTC()),
                                signupTrustedProxies, problemWriter, signupMaxBodyBytes),
                        UsernamePasswordAuthenticationFilter.class)
                // 파일 업로드도 로그인 없이 받는다 — 같은 곳에서 되풀이하면 본문을 읽기 전에 거절한다. 프록시 수는 가입과 같다
                .addFilterBefore(new UploadRateLimitFilter(
                                new SignupRateLimiter(uploadMaxAttempts, Duration.ofMinutes(uploadWindowMinutes),
                                        Clock.systemUTC()),
                                new SignupRateLimiter(uploadMaxPerDay, Duration.ofDays(1), Clock.systemUTC()),
                                signupTrustedProxies, problemWriter),
                        UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(new ApiKeyAuthenticationFilter(internalApiKey),
                        UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(new JwtAuthenticationFilter(jwtProvider),
                        UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
