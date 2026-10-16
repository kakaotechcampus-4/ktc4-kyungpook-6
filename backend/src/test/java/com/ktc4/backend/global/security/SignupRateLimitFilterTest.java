package com.ktc4.backend.global.security;

import com.ktc4.backend.domain.auth.controller.AuthController;
import com.ktc4.backend.domain.auth.service.AuthService;
import com.ktc4.backend.global.error.CustomException;
import com.ktc4.backend.global.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.net.URI;
import java.util.Collections;
import java.util.Enumeration;

import static com.ktc4.backend.support.SignupMultipart.SIGNUP_URL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 가입 신청 횟수 제한 확인. 한도를 낮춰(시도 3번, 하루 성공 2건) 실제 보안 필터 체인으로 본다.
 *
 * <p>횟수는 필터가 메모리에 들고 있어 테스트끼리 섞인다. 그래서 테스트마다 컨텍스트를 새로 만든다.
 * IP 는 문서용으로 예약된 대역(RFC 5737, RFC 3849)의 값이다 — 실제 기기에 배정되지 않는다.
 */
@WebMvcTest(AuthController.class)
@Import(SecurityConfig.class)
@TestPropertySource(properties = {
        "signup.rate-limit.max-attempts=3", "signup.rate-limit.window-minutes=10",
        "signup.rate-limit.max-signups-per-day=2", "signup.rate-limit.trusted-proxies=1",
        "signup.max-body-bytes=2048"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
@DisplayName("가입 신청 횟수·본문 크기 제한")
class SignupRateLimitFilterTest {

    // 글자 정보가 틀린 요청 — 컨트롤러가 400 으로 거절한다. 실패한 요청도 세는지 보려고 쓴다
    private static final String INVALID_DATA = "{}";
    // 검증을 통과하는 글자 정보. 서비스(가짜)까지 간다
    private static final String VALID_DATA =
            "{\"email\":\"owner@example.com\",\"password\":\"password1234\",\"bizNo\":\"1234567890\","
                    + "\"storeName\":\"예시분식\",\"representativeName\":\"홍길동\",\"phone\":\"010-0000-0000\","
                    + "\"storeAddress\":\"가상특별시 예시구 샘플로 123\",\"storePhone\":\"053-000-0000\","
                    + "\"businessHours\":[{\"day\":\"MON\",\"open\":\"11:00\",\"close\":\"21:00\"}],"
                    + "\"menus\":[{\"name\":\"김치찌개\"}],"
                    + "\"bizCertFileId\":\"7b1d0e4f-2c3a-4d5b-8e6f-9a0b1c2d3e4f\"}";
    // 우리 프록시의 주소 — 프록시 뒤에서는 모든 요청의 접속 주소가 이 값이다
    private static final String PROXY = "192.0.2.1";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AuthService authService;

    private static MockHttpServletRequestBuilder signup(URI uri, String dataJson) {
        return post(uri).contentType(MediaType.APPLICATION_JSON).content(dataJson);
    }

    /** 프록시 없이 {@code ip} 에서 직접 온 요청. */
    private static MockHttpServletRequestBuilder signupFrom(String ip, String dataJson) {
        MockHttpServletRequestBuilder request = signup(URI.create(SIGNUP_URL), dataJson);
        request.with(servletRequest -> {
            servletRequest.setRemoteAddr(ip);
            return servletRequest;
        });
        return request;
    }

    /** 우리 프록시를 거쳐 온 요청 — 접속 주소는 프록시이고, 보낸 쪽은 X-Forwarded-For 에 실려 온다. */
    private static MockHttpServletRequestBuilder signupViaProxy(String forwardedFor) {
        MockHttpServletRequestBuilder request = signupFrom(PROXY, INVALID_DATA);
        request.header("X-Forwarded-For", forwardedFor);
        return request;
    }

    @Nested
    @DisplayName("시도 횟수")
    class Attempts {

        @Test
        @DisplayName("같은 IP 에서 한도를 넘기면 429 too-many-requests 이고, 팀 에러 규격과 Retry-After 가 실린다")
        void rejectsOverLimit() throws Exception {
            for (int i = 0; i < 3; i++) {
                mockMvc.perform(signupFrom("203.0.113.10", INVALID_DATA)).andExpect(status().isBadRequest());
            }

            String retryAfter = mockMvc.perform(signupFrom("203.0.113.10", INVALID_DATA))
                    .andExpect(status().isTooManyRequests())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.type").value(ErrorCode.TOO_MANY_REQUESTS.getType().toString()))
                    .andExpect(jsonPath("$.title").value(ErrorCode.TOO_MANY_REQUESTS.getTitle()))
                    .andExpect(jsonPath("$.status").value(429))
                    .andExpect(jsonPath("$.detail").doesNotExist())
                    .andExpect(header().exists(HttpHeaders.RETRY_AFTER))
                    .andReturn().getResponse().getHeader(HttpHeaders.RETRY_AFTER);

            assertThat(Long.parseLong(retryAfter)).isBetween(1L, 600L);
        }

        @Test
        @DisplayName("거절된 요청은 컨트롤러에 닿지 않는다 — 본문을 읽기 전에 막는다")
        void rejectedRequestNeverReachesController() throws Exception {
            // 서비스가 거절하는 가입(성공이 아님) — 하루 성공 수가 아니라 시도 횟수에 걸리게 한다
            when(authService.signupOwner(any())).thenThrow(new CustomException(ErrorCode.INVALID_PHONE));

            for (int i = 0; i < 5; i++) {
                mockMvc.perform(signupFrom("203.0.113.20", VALID_DATA));
            }

            // 다섯 번 보냈지만 서비스까지 간 것은 한도인 세 번뿐이다
            verify(authService, times(3)).signupOwner(any());
        }

        @Test
        @DisplayName("경로의 글자를 인코딩해 보내도 같은 가입 신청으로 센다 — 문자열만 비교하면 횟수 제한을 피해 갈 수 있다")
        void countsEncodedPathToo() throws Exception {
            // URI 로 넘겨 "%" 가 다시 인코딩되지 않게 한다. 컨트롤러에는 "/api/auth/owners/signup" 으로 닿는다
            URI encoded = URI.create("/api/auth/owners/sign%75p");
            for (int i = 0; i < 3; i++) {
                mockMvc.perform(signup(encoded, INVALID_DATA).with(servletRequest -> {
                            servletRequest.setRemoteAddr("203.0.113.50");
                            return servletRequest;
                        }))
                        .andExpect(status().isBadRequest());   // 컨트롤러까지 닿았다(글자 정보 검증 실패)
            }

            mockMvc.perform(signup(encoded, INVALID_DATA).with(servletRequest -> {
                        servletRequest.setRemoteAddr("203.0.113.50");
                        return servletRequest;
                    }))
                    .andExpect(status().isTooManyRequests());
            // 인코딩한 경로로 채운 횟수가 원래 경로에도 그대로 적용된다
            mockMvc.perform(signupFrom("203.0.113.50", INVALID_DATA)).andExpect(status().isTooManyRequests());
        }

        @Test
        @DisplayName("IP 가 다르면 따로 센다")
        void countsPerIp() throws Exception {
            for (int i = 0; i < 4; i++) {
                mockMvc.perform(signupFrom("203.0.113.30", INVALID_DATA));
            }

            mockMvc.perform(signupFrom("203.0.113.30", INVALID_DATA)).andExpect(status().isTooManyRequests());
            mockMvc.perform(signupFrom("198.51.100.30", INVALID_DATA)).andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("가입 신청만 센다 — 로그인은 가입이 막힌 IP 에서도 그대로 된다")
        void limitsOnlySignup() throws Exception {
            for (int i = 0; i < 4; i++) {
                mockMvc.perform(signupFrom("203.0.113.40", INVALID_DATA));
            }
            mockMvc.perform(signupFrom("203.0.113.40", INVALID_DATA)).andExpect(status().isTooManyRequests());

            for (int i = 0; i < 5; i++) {
                mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                                .content("{\"email\":\"owner@example.com\",\"password\":\"password1234\"}")
                                .with(servletRequest -> {
                                    servletRequest.setRemoteAddr("203.0.113.40");
                                    return servletRequest;
                                }))
                        .andExpect(status().isOk());
            }
        }
    }

    @Nested
    @DisplayName("하루에 성공할 수 있는 가입 수")
    class SignupsPerDay {

        @Test
        @DisplayName("같은 곳에서 하루 한도만큼 가입에 성공하면 그다음은 429 — 시도 횟수가 남아 있어도")
        void limitsSuccessfulSignups() throws Exception {
            // 시도 한도는 3번, 하루 성공 한도는 2건이다. 세 번째는 시도로는 허용되지만 성공 한도에 걸린다
            mockMvc.perform(signupFrom("203.0.113.70", VALID_DATA)).andExpect(status().isCreated());
            mockMvc.perform(signupFrom("203.0.113.70", VALID_DATA)).andExpect(status().isCreated());

            mockMvc.perform(signupFrom("203.0.113.70", VALID_DATA))
                    .andExpect(status().isTooManyRequests())
                    .andExpect(header().exists(HttpHeaders.RETRY_AFTER));
            verify(authService, times(2)).signupOwner(any());
        }

        @Test
        @DisplayName("실패한 가입은 성공 수로 세지 않는다 — 입력을 틀려 다시 시도하는 사람이 하루 한도를 쓰지 않게")
        void failedSignupsDoNotCount() throws Exception {
            mockMvc.perform(signupFrom("203.0.113.80", INVALID_DATA)).andExpect(status().isBadRequest());
            mockMvc.perform(signupFrom("203.0.113.80", INVALID_DATA)).andExpect(status().isBadRequest());

            // 실패 두 번 뒤에도 성공 한도는 그대로라 가입된다(시도로는 세 번째라 허용)
            mockMvc.perform(signupFrom("203.0.113.80", VALID_DATA)).andExpect(status().isCreated());
        }

        @Test
        @DisplayName("다른 곳의 가입은 영향받지 않는다")
        void countsPerIp() throws Exception {
            mockMvc.perform(signupFrom("203.0.113.90", VALID_DATA)).andExpect(status().isCreated());
            mockMvc.perform(signupFrom("203.0.113.90", VALID_DATA)).andExpect(status().isCreated());
            mockMvc.perform(signupFrom("203.0.113.90", VALID_DATA)).andExpect(status().isTooManyRequests());

            mockMvc.perform(signupFrom("198.51.100.90", VALID_DATA)).andExpect(status().isCreated());
        }
    }

    @Nested
    @DisplayName("프록시 뒤에서 — 보낸 쪽을 X-Forwarded-For 의 오른쪽에서 정한다")
    class BehindProxy {

        @Test
        @DisplayName("헤더의 왼쪽 값을 매번 바꿔 보내도 같은 곳으로 센다 — 왼쪽은 보내는 쪽이 마음대로 적는 값이다")
        void spoofedLeftmostValueDoesNotEvadeLimit() throws Exception {
            // 프록시가 덧붙이는 방식: "보낸 쪽이 적은 값, 프록시가 본 실제 주소"
            mockMvc.perform(signupViaProxy("198.51.100.1, 203.0.113.60")).andExpect(status().isBadRequest());
            mockMvc.perform(signupViaProxy("198.51.100.2, 203.0.113.60")).andExpect(status().isBadRequest());
            mockMvc.perform(signupViaProxy("198.51.100.3, 203.0.113.60")).andExpect(status().isBadRequest());

            mockMvc.perform(signupViaProxy("198.51.100.4, 203.0.113.60")).andExpect(status().isTooManyRequests());
        }

        @Test
        @DisplayName("프록시가 본 주소가 다르면 따로 센다 — 모든 사용자가 프록시 주소 하나로 묶이지 않는다")
        void countsRealClientsSeparately() throws Exception {
            for (int i = 0; i < 4; i++) {
                mockMvc.perform(signupViaProxy("203.0.113.61"));
            }

            mockMvc.perform(signupViaProxy("203.0.113.61")).andExpect(status().isTooManyRequests());
            mockMvc.perform(signupViaProxy("203.0.113.62")).andExpect(status().isBadRequest());
        }
    }

    @Nested
    @DisplayName("IPv6 — 주소 하나가 아니라 /64 대역으로 센다")
    class Ipv6 {

        @Test
        @DisplayName("같은 /64 안에서 주소를 바꿔 가며 보내도 같은 곳으로 센다")
        void sameSlash64SharesLimit() throws Exception {
            mockMvc.perform(signupFrom("2001:db8:1:2::1", INVALID_DATA)).andExpect(status().isBadRequest());
            mockMvc.perform(signupFrom("2001:db8:1:2::2", INVALID_DATA)).andExpect(status().isBadRequest());
            mockMvc.perform(signupFrom("2001:db8:1:2:aaaa:bbbb:cccc:dddd", INVALID_DATA))
                    .andExpect(status().isBadRequest());

            mockMvc.perform(signupFrom("2001:db8:1:2:ffff:ffff:ffff:ffff", INVALID_DATA))
                    .andExpect(status().isTooManyRequests());
        }

        @Test
        @DisplayName("/64 가 다르면 따로 센다")
        void differentSlash64IsSeparate() throws Exception {
            for (int i = 0; i < 4; i++) {
                mockMvc.perform(signupFrom("2001:db8:9:2::" + (i + 1), INVALID_DATA));
            }

            mockMvc.perform(signupFrom("2001:db8:9:2::9", INVALID_DATA)).andExpect(status().isTooManyRequests());
            mockMvc.perform(signupFrom("2001:db8:9:3::1", INVALID_DATA)).andExpect(status().isBadRequest());
        }
    }

    @Nested
    @DisplayName("보낸 쪽 IP 정하기 (clientIp)")
    class ClientIp {

        private static MockHttpServletRequest request(String remoteAddr, String... forwardedForLines) {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.setRemoteAddr(remoteAddr);
            for (String line : forwardedForLines) {
                request.addHeader("X-Forwarded-For", line);
            }
            return request;
        }

        @Test
        @DisplayName("프록시가 한 대면 헤더의 맨 오른쪽 값이다 — 프록시가 덮어썼든 덧붙였든")
        void oneProxyTakesRightmost() {
            assertThat(SignupRateLimitFilter.clientIp(request(PROXY, "203.0.113.5"), 1)).isEqualTo("203.0.113.5");
            assertThat(SignupRateLimitFilter.clientIp(request(PROXY, "198.51.100.9, 203.0.113.5"), 1))
                    .isEqualTo("203.0.113.5");
            // 헤더가 여러 줄로 나뉘어 와도 이어 붙인 순서의 맨 끝이다
            assertThat(SignupRateLimitFilter.clientIp(request(PROXY, "198.51.100.9", "203.0.113.5"), 1))
                    .isEqualTo("203.0.113.5");
        }

        @Test
        @DisplayName("프록시가 두 대면 오른쪽에서 두 번째 값이다")
        void twoProxiesTakeSecondFromRight() {
            assertThat(SignupRateLimitFilter.clientIp(request(PROXY, "198.51.100.9, 203.0.113.5, 192.0.2.7"), 2))
                    .isEqualTo("203.0.113.5");
        }

        @Test
        @DisplayName("헤더가 없거나 칸이 프록시 수보다 적으면 실제 접속 주소다 — 프록시를 거치지 않은 요청")
        void fallsBackToRemoteAddr() {
            assertThat(SignupRateLimitFilter.clientIp(request("203.0.113.5"), 1)).isEqualTo("203.0.113.5");
            assertThat(SignupRateLimitFilter.clientIp(request("203.0.113.5", "198.51.100.9"), 2))
                    .isEqualTo("203.0.113.5");
        }

        @Test
        @DisplayName("프록시가 없다고 설정하면(0) 헤더를 믿지 않는다 — 보내는 쪽이 헤더를 지어내도 실제 접속 주소를 쓴다")
        void zeroProxiesIgnoresHeader() {
            assertThat(SignupRateLimitFilter.clientIp(request("203.0.113.5", "198.51.100.9"), 0))
                    .isEqualTo("203.0.113.5");
        }

        @Test
        @DisplayName("앞의 필터가 헤더를 지우고 접속 주소를 바꿔 놓았어도 원래 요청에서 읽는다")
        void readsThroughWrappers() {
            // X-Forwarded-For 를 해석하는 필터가 하는 일을 흉내 낸다 — 헤더를 숨기고 맨 왼쪽 값을 접속 주소로 내놓는다
            HttpServletRequest original = request(PROXY, "198.51.100.9, 203.0.113.5");
            HttpServletRequest wrapped = new HttpServletRequestWrapper(original) {
                @Override
                public String getRemoteAddr() {
                    return "198.51.100.9";
                }

                @Override
                public Enumeration<String> getHeaders(String name) {
                    return Collections.emptyEnumeration();
                }
            };

            assertThat(SignupRateLimitFilter.clientIp(wrapped, 1)).isEqualTo("203.0.113.5");
        }
    }

    @ParameterizedTest(name = "[{index}] {0} → {1}")
    @CsvSource({
            "203.0.113.10,                    203.0.113.10",
            "2001:db8:1:2:3:4:5:6,            2001:db8:1:2::/64",
            "2001:db8:1:2::1,                 2001:db8:1:2::/64",
            "2001:DB8:1:2::ABCD,              2001:db8:1:2::/64",
            // IPv4 를 IPv6 모양으로 적은 주소는 IPv4 로 센다 — 표기만 바꿔 따로 세지지 않게
            "::ffff:203.0.113.10,             203.0.113.10",
            "not-an-ip,                       not-an-ip"
    })
    @DisplayName("세는 단위 — IPv4 는 주소 하나, IPv6 는 /64, 표기가 달라도 같은 값")
    void bucketKeys(String ip, String key) {
        assertThat(SignupRateLimitFilter.bucketKey(ip)).isEqualTo(key);
    }

    @Test
    @DisplayName("IP 모양이 아닌 긴 값은 잘라서 센다 — 지어낸 긴 문자열로 기록을 키우지 못하게")
    void bucketKeyTruncatesGarbage() {
        assertThat(SignupRateLimitFilter.bucketKey("x".repeat(500))).hasSize(45);
        assertThat(SignupRateLimitFilter.bucketKey(null)).isEqualTo("unknown");
    }

    @ParameterizedTest(name = "[{index}] {0} → {1}")
    @CsvSource({
            "203.0.113.10,                    203.0.113.***",
            "2001:db8:1:2:3:4:5:6,            2001:db8:1:2:***",
            "::1,                             ***"
    })
    @DisplayName("로그에 남기는 IP 는 끝자리를 가린다")
    void masksIp(String ip, String masked) {
        assertThat(SignupRateLimitFilter.maskIp(ip)).isEqualTo(masked);
    }

    @Nested
    @DisplayName("본문 크기 (이 테스트의 상한 2048바이트)")
    class BodySize {

        // 공백으로 채운 JSON 객체 — 크기만 맞춘다. 칸이 비어 있어 상한을 통과하면 칸별 검증에서 400 이 난다
        private MockHttpServletRequestBuilder signupWithBodyOf(String ip, int bytes) {
            return signupFrom(ip, "{" + " ".repeat(bytes - 2) + "}");
        }

        @Test
        @DisplayName("상한을 1바이트라도 넘기면 413 request-too-large 이고 컨트롤러에 닿지 않는다 — 팀 에러 규격으로")
        void rejectsOverLimit() throws Exception {
            mockMvc.perform(signupWithBodyOf("203.0.113.50", 2049))
                    .andExpect(status().isPayloadTooLarge())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.type").value(ErrorCode.REQUEST_TOO_LARGE.getType().toString()))
                    .andExpect(jsonPath("$.title").value(ErrorCode.REQUEST_TOO_LARGE.getTitle()))
                    .andExpect(jsonPath("$.status").value(413))
                    .andExpect(jsonPath("$.detail").doesNotExist());

            verify(authService, never()).signupOwner(any());
        }

        @Test
        @DisplayName("상한과 같은 크기는 통과한다 — 그다음 칸별 검증이 본다")
        void allowsExactLimit() throws Exception {
            mockMvc.perform(signupWithBodyOf("203.0.113.51", 2048))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.type").value(ErrorCode.INVALID_REQUEST.getType().toString()));
        }

        @Test
        @DisplayName("너무 큰 요청도 한 번의 시도로 센다 — 413 을 되풀이하면 한도 뒤에는 429 다")
        void countsTooLargeAsAttempt() throws Exception {
            for (int i = 0; i < 3; i++) {
                mockMvc.perform(signupWithBodyOf("203.0.113.53", 2049)).andExpect(status().isPayloadTooLarge());
            }

            mockMvc.perform(signupWithBodyOf("203.0.113.53", 2049)).andExpect(status().isTooManyRequests());
            mockMvc.perform(signupFrom("203.0.113.53", VALID_DATA)).andExpect(status().isTooManyRequests());
            verify(authService, never()).signupOwner(any());
        }

        @Test
        @DisplayName("길이를 알리지 않고 보낸 큰 본문은 읽다가 상한에서 멈춰 400 이고 서비스에 닿지 않는다")
        void stopsUndeclaredLengthBodyAtLimit() throws Exception {
            // 상한이 없으면 201 이 될 올바른 신청을 공백으로 부풀려 상한(2048)을 넘긴다 — 400 과 never() 가 상한 때문에만
            // 성립하게. 길이를 알리지 않으면(-1) 필터가 읽기 전에 거절할 수 없어, 읽다가 멈추는 쪽이 막아야 한다
            String body = VALID_DATA.substring(0, VALID_DATA.length() - 1) + " ".repeat(3000) + "}";
            MockHttpServletRequestBuilder request = signupFrom("203.0.113.54", body);
            request.with(servletRequest -> {
                MockHttpServletRequest undeclared = spy(servletRequest);
                doReturn(-1L).when(undeclared).getContentLengthLong();
                doReturn(-1).when(undeclared).getContentLength();
                return undeclared;
            });

            mockMvc.perform(request)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.type").value(ErrorCode.INVALID_REQUEST.getType().toString()));
            verify(authService, never()).signupOwner(any());
        }

        @Test
        @DisplayName("보통 크기의 가입 신청은 그대로 받는다")
        void allowsNormalSignup() throws Exception {
            mockMvc.perform(signupFrom("203.0.113.52", VALID_DATA)).andExpect(status().isCreated());

            verify(authService).signupOwner(any());
        }
    }
}
