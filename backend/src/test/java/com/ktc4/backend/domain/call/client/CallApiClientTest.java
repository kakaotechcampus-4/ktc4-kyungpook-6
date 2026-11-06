package com.ktc4.backend.domain.call.client;

import com.ktc4.backend.domain.call.dto.CallOrder;
import com.ktc4.backend.domain.call.dto.CallQuestion;
import com.ktc4.backend.domain.call.dto.CallQuestionField;
import com.ktc4.backend.domain.call.dto.CallSnapshot;
import com.ktc4.backend.domain.call.entity.CallAnswer;
import com.ktc4.backend.domain.call.enums.CallField;
import com.ktc4.backend.domain.call.enums.CallStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * {@link CallApiClient} — 전화 API 의 요청·응답을 우리 객체·예외로 바꾸는 경계.
 *
 * <p>응답 샘플은 손으로 지어내지 않고 <b>전화 API 명세의 예시를 그대로 옮겼다</b>. 우리 DTO 를 보고
 * JSON 을 쓰면 우리 가정끼리만 맞는 테스트가 된다({@code AiApiClientTest} 와 같은 이유).
 *
 * <p>요청 본문도 함께 본다 — 두 질문 방식이 쓰는 칸이 다르고, 영업 여부만 JSON 불리언으로 나가야 한다.
 * 이것이 어긋나면 전화 API 가 거절하거나, 더 나쁘게는 엉뚱한 것을 묻는 통화가 실제로 걸린다.
 */
@DisplayName("전화 API 클라이언트")
class CallApiClientTest {

    private static final String BASE_URL = "http://call.test";
    private static final String CALL_URL = BASE_URL + "/v1/verification-call";
    private static final String VERIFICATION_ID = "11111111-2222-4333-8444-555555555555";

    private MockRestServiceServer server;
    private CallApiClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder()
                .baseUrl(BASE_URL)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer 전화키");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new CallApiClient(builder.build());
    }

    @Nested
    @DisplayName("전화 요청 (POST)")
    class Request {

        private final CallOrder order = new CallOrder("예시분식", "대구광역시 북구 대학로 80", "053-111-1111",
                List.of(CallQuestion.valueConfirmation("q1",
                        new CallQuestionField(CallField.OPEN, "true"), "false")));

        @Test
        @DisplayName("명세의 응답 예시에서 verificationId 를 꺼낸다")
        void verificationId_를_꺼낸다() {
            server.expect(requestTo(CALL_URL))
                    .andExpect(method(HttpMethod.POST))
                    .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer 전화키"))
                    .andRespond(withStatus(HttpStatus.ACCEPTED)
                            .contentType(MediaType.APPLICATION_JSON)
                            .body("{\"verificationId\": \"" + VERIFICATION_ID + "\"}"));

            assertThat(client.request(order)).isEqualTo(VERIFICATION_ID);
            server.verify();
        }

        @Test
        @DisplayName("걸 곳을 target 에 담는다 — 가게 전화번호만 보내고 점주 개인 번호·사업자번호는 보내지 않는다")
        void target_을_담는다() {
            server.expect(requestTo(CALL_URL))
                    .andExpect(jsonPath("$.target.storeName").value("예시분식"))
                    .andExpect(jsonPath("$.target.storeLocation").value("대구광역시 북구 대학로 80"))
                    .andExpect(jsonPath("$.target.phoneNumber").value("053-111-1111"))
                    .andRespond(accepted());

            client.request(order);
            server.verify();
        }

        @Test
        @DisplayName("확인 질문은 field·currentValue·expectedValue 로 보내고 fields 는 비운다")
        void 확인_질문의_모양() {
            server.expect(requestTo(CALL_URL))
                    .andExpect(jsonPath("$.questions[0].id").value("q1"))
                    .andExpect(jsonPath("$.questions[0].type").value("VALUE_CONFIRMATION"))
                    .andExpect(jsonPath("$.questions[0].field").value("open"))
                    .andExpect(jsonPath("$.questions[0].naturalField").value("영업 여부"))
                    // 영업 여부는 JSON 불리언이다. 문자열 "true" 로 나가면 명세의 타입 규칙을 어긴다
                    .andExpect(jsonPath("$.questions[0].currentValue").value(true))
                    .andExpect(jsonPath("$.questions[0].expectedValue").value(false))
                    .andExpect(jsonPath("$.questions[0].fields").doesNotExist())
                    .andRespond(accepted());

            client.request(order);
            server.verify();
        }

        @Test
        @DisplayName("변화 확인 질문은 fields 배열로 보내고 field·expectedValue 는 비운다")
        void 변화_확인_질문의_모양() {
            CallOrder recheck = new CallOrder("예시분식", "대구광역시 북구 대학로 80", "053-111-1111",
                    List.of(CallQuestion.changeDetection("q1", List.of(
                            new CallQuestionField(CallField.STORE_NAME, "예시분식"),
                            new CallQuestionField(CallField.STORE_LOCATION, "대구광역시 북구 대학로 80")))));

            server.expect(requestTo(CALL_URL))
                    .andExpect(jsonPath("$.questions[0].type").value("CHANGE_DETECTION"))
                    .andExpect(jsonPath("$.questions[0].fields[0].field").value("storeName"))
                    .andExpect(jsonPath("$.questions[0].fields[0].naturalField").value("상호명"))
                    .andExpect(jsonPath("$.questions[0].fields[0].currentValue").value("예시분식"))
                    .andExpect(jsonPath("$.questions[0].fields[1].field").value("storeLocation"))
                    .andExpect(jsonPath("$.questions[0].field").doesNotExist())
                    .andExpect(jsonPath("$.questions[0].expectedValue").doesNotExist())
                    .andRespond(accepted());

            client.request(recheck);
            server.verify();
        }

        @Test
        @DisplayName("verificationId 가 없는 응답은 약속 위반이다")
        void verificationId_가_없으면_실패() {
            server.expect(requestTo(CALL_URL)).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

            assertThatThrownBy(() -> client.request(order)).isInstanceOf(CallContractError.class);
        }

        private org.springframework.test.web.client.response.DefaultResponseCreator accepted() {
            return withStatus(HttpStatus.ACCEPTED)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{\"verificationId\": \"" + VERIFICATION_ID + "\"}");
        }
    }

    @Nested
    @DisplayName("상태 조회 (GET)")
    class Read {

        // 전화 API 명세의 응답 예시 그대로 (우리가 받지 않는 createdAt 포함)
        private static final String SPEC_SAMPLE = """
                {
                  "verificationId": "11111111-2222-4333-8444-555555555555",
                  "status": "COMPLETED",
                  "answers": [
                    { "questionId": "q1", "field": "storeName", "value": "프롬프트 베이커리" },
                    { "questionId": "q1", "field": "storeLocation", "value": "서울특별시 강남구 테스트로 1" },
                    { "questionId": "q2", "field": "storeName", "value": "프롬프트 베이커리" }
                  ],
                  "createdAt": "2026-11-01T00:00:00+00:00",
                  "startedAt": "2026-11-01T00:00:04+00:00",
                  "connectedAt": "2026-11-01T00:00:10+00:00",
                  "endedAt": "2026-11-01T00:01:10+00:00",
                  "failureReason": null
                }
                """;

        @Test
        @DisplayName("명세의 응답 예시를 상태·답·시각으로 읽는다")
        void 명세_예시를_읽는다() {
            server.expect(requestTo(CALL_URL + "/" + VERIFICATION_ID))
                    .andExpect(method(HttpMethod.GET))
                    .andRespond(withSuccess(SPEC_SAMPLE, MediaType.APPLICATION_JSON));

            CallSnapshot snapshot = client.read(VERIFICATION_ID);

            assertThat(snapshot.status()).isEqualTo(CallStatus.COMPLETED);
            assertThat(snapshot.failureReason()).isNull();
            assertThat(snapshot.answers())
                    .extracting(CallAnswer::questionId, CallAnswer::field, CallAnswer::value)
                    .containsExactly(
                            tuple("q1", "storeName", "프롬프트 베이커리"),
                            tuple("q1", "storeLocation", "서울특별시 강남구 테스트로 1"),
                            tuple("q2", "storeName", "프롬프트 베이커리"));
            // 전화 API 는 UTC 로 보내고 우리는 앱의 다른 시각과 나란히 비교할 수 있게 우리 시간대로 옮겨 적는다
            assertThat(snapshot.connectedAt()).isEqualTo(localTime("2026-11-01T00:00:10+00:00"));
            assertThat(snapshot.endedAt()).isEqualTo(localTime("2026-11-01T00:01:10+00:00"));
        }

        @Test
        @DisplayName("참·거짓 답도 글자로 남긴다")
        void 불리언_답도_글자로() {
            server.expect(requestTo(CALL_URL + "/" + VERIFICATION_ID)).andRespond(withSuccess("""
                    { "verificationId": "%s", "status": "COMPLETED",
                      "answers": [{ "questionId": "q1", "field": "open", "value": false }] }
                    """.formatted(VERIFICATION_ID), MediaType.APPLICATION_JSON));

            assertThat(client.read(VERIFICATION_ID).answers())
                    .extracting(CallAnswer::value).containsExactly("false");
        }

        @Test
        @DisplayName("진행 중이면 끝난 시각이 없다")
        void 진행_중이면_끝난_시각이_없다() {
            server.expect(requestTo(CALL_URL + "/" + VERIFICATION_ID)).andRespond(withSuccess("""
                    { "verificationId": "%s", "status": "IN_PROGRESS", "answers": [],
                      "startedAt": "2026-11-01T00:00:04+00:00", "connectedAt": null, "endedAt": null,
                      "failureReason": null }
                    """.formatted(VERIFICATION_ID), MediaType.APPLICATION_JSON));

            CallSnapshot snapshot = client.read(VERIFICATION_ID);

            assertThat(snapshot.status()).isEqualTo(CallStatus.IN_PROGRESS);
            assertThat(snapshot.startedAt()).isEqualTo(localTime("2026-11-01T00:00:04+00:00"));
            assertThat(snapshot.connectedAt()).isNull();
            assertThat(snapshot.endedAt()).isNull();
            assertThat(snapshot.answers()).isEmpty();
        }

        @Test
        @DisplayName("모르는 status 는 약속 위반이다 — 모르는 값을 그냥 지나가면 끝난 전화를 진행 중으로 본다")
        void 모르는_status() {
            server.expect(requestTo(CALL_URL + "/" + VERIFICATION_ID)).andRespond(withSuccess("""
                    { "verificationId": "%s", "status": "RINGING" }
                    """.formatted(VERIFICATION_ID), MediaType.APPLICATION_JSON));

            assertThatThrownBy(() -> client.read(VERIFICATION_ID))
                    .isInstanceOf(CallContractError.class)
                    .hasMessageContaining("RINGING");
        }

        private static LocalDateTime localTime(String text) {
            return OffsetDateTime.parse(text).atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime();
        }
    }

    @Nested
    @DisplayName("오류 번역")
    class Errors {

        @ParameterizedTest(name = "HTTP {0} → 다시 시도할 만한 실패")
        @ValueSource(ints = {500, 502, 503})
        void 서버_오류는_일시_실패(int status) {
            server.expect(requestTo(CALL_URL + "/" + VERIFICATION_ID)).andRespond(withStatus(HttpStatus.valueOf(status)));

            assertThatThrownBy(() -> client.read(VERIFICATION_ID)).isInstanceOf(CallTransientError.class);
        }

        @ParameterizedTest(name = "HTTP {0} → 다시 보내도 같은 실패")
        @ValueSource(ints = {400, 401, 404, 422})
        void 거절은_약속_위반(int status) {
            server.expect(requestTo(CALL_URL + "/" + VERIFICATION_ID)).andRespond(withStatus(HttpStatus.valueOf(status)));

            assertThatThrownBy(() -> client.read(VERIFICATION_ID)).isInstanceOf(CallContractError.class);
        }

        @Test
        @DisplayName("2xx 인데 본문을 해석할 수 없으면 약속 위반이다")
        void 깨진_본문() {
            server.expect(requestTo(CALL_URL + "/" + VERIFICATION_ID))
                    .andRespond(withSuccess("{깨진 JSON", MediaType.APPLICATION_JSON));

            assertThatThrownBy(() -> client.read(VERIFICATION_ID)).isInstanceOf(CallContractError.class);
        }
    }

    @Nested
    @DisplayName("주소·키를 넣지 않은 환경")
    class NotConfigured {

        @ParameterizedTest(name = "주소=[{0}] 키=[{1}]")
        @org.junit.jupiter.params.provider.CsvSource(value = {
                "'',''", "http://call.test,''", "'',전화키"
        })
        @DisplayName("둘 중 하나라도 비면 쓸 수 없다고 답하고, 부르면 거절한다")
        void 값이_없으면_쓸_수_없다(String baseUrl, String apiKey) {
            CallApiClient notConfigured = new CallApiClient(
                    RestClient.builder(), baseUrl, apiKey, 3000, 5000);

            assertThat(notConfigured.isConfigured()).isFalse();
            assertThatThrownBy(() -> notConfigured.read(VERIFICATION_ID)).isInstanceOf(CallNotConfigured.class);
        }

        @Test
        @DisplayName("둘 다 있으면 쓸 수 있다 — 기동할 때 전화 API 에 닿아 보지는 않는다")
        void 값이_있으면_쓸_수_있다() {
            assertThat(new CallApiClient(RestClient.builder(), BASE_URL, "전화키", 3000, 5000).isConfigured()).isTrue();
        }
    }
}
