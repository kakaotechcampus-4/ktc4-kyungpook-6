package com.ktc4.backend.domain.investigation.client;

import com.ktc4.backend.domain.investigation.dto.AiFinding;
import com.ktc4.backend.domain.investigation.dto.AiSignal;
import com.ktc4.backend.domain.investigation.dto.InvestigationTarget;
import com.ktc4.backend.domain.signal.enums.ChangeField;
import com.ktc4.backend.domain.signal.enums.SignalType;
import com.ktc4.backend.domain.store.enums.StoreStatus;
import com.ktc4.backend.domain.task.enums.TaskClassification;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * {@link AiApiClient} — AI 서버 응답을 우리 객체·예외로 바꾸는 경계.
 *
 * <p>상태코드·본문 해석은 {@link MockRestServiceServer} 로, 타임아웃·연결 실패는 실제 소켓(JDK HTTP 서버)으로 본다.
 * {@code MockRestServiceServer} 는 요청 팩토리를 가짜로 바꿔서, JDK HttpClient 가 실제로 던지는 예외 타입을 만들지 못한다.
 *
 * <p>정상 응답 샘플은 손으로 지어내지 않고 AI 연동 문서({@code ai/docs/백엔드_연동.md}) 예시를 그대로 옮겼다 —
 * 우리 DTO 를 보고 JSON 을 쓰면 우리 가정끼리만 맞는 테스트가 된다.
 */
@DisplayName("AI 조사 클라이언트")
class AiApiClientTest {

    private static final String BASE_URL = "http://ai.test";
    private static final String INVESTIGATIONS_URL = BASE_URL + "/investigations";

    private static final InvestigationTarget TARGET = new InvestigationTarget(
            44L, "예시분식", "대구광역시 북구 대학로 80", "1234567890", "053-111-1111",
            StoreStatus.OPEN, 35.89, 128.61);

    // ai/docs/백엔드_연동.md 의 응답 예시 그대로 (mapCheck·evidences·sourceCount 처럼 우리가 안 받는 필드 포함)
    private static final String DOC_SAMPLE = """
            {
              "results": [{
                "storeId": 44,
                "classification": "PRIORITY_CHECK",
                "proposedChanges": { "phone": "053-964-0022" },
                "signals": [
                  { "signalType": "SIGNAL_HIGH", "field": "phone", "observed": "053-964-0022",
                    "evidenceText": "전화번호: 전화번호 053-964-0022", "evidenceUrl": "https://example.com/a", "sourceCount": 2 }
                ],
                "mapCheck": { "status": "FOUND", "placeUrl": "http://place.map.kakao.com/10065017" },
                "evidences": [],
                "failure": null
              }],
              "requested": 1,
              "succeeded": 1
            }
            """;

    private MockRestServiceServer server;
    private AiApiClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        server = MockRestServiceServer.bindTo(builder).build();
        client = new AiApiClient(builder.build());
    }

    private void respond(String json) {
        server.expect(requestTo(INVESTIGATIONS_URL))
                .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));
    }

    private static String result(String body) {
        return "{\"results\": [" + body + "], \"requested\": 1, \"succeeded\": 1}";
    }

    @Nested
    @DisplayName("정상 응답")
    class Success {

        @Test
        @DisplayName("가게 한 곳을 한 건짜리 목록으로 보낸다 — AI 가 받는 필드 이름 그대로")
        void sendsSingleTargetList() {
            server.expect(requestTo(INVESTIGATIONS_URL))
                    .andExpect(method(HttpMethod.POST))
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$.length()").value(1))
                    .andExpect(jsonPath("$[0].storeId").value(44))
                    .andExpect(jsonPath("$[0].name").value("예시분식"))
                    // AI 는 address 가 아니라 addressRoad 로 받는다 — 이름이 틀리면 AI 쪽에서 조용히 None 이 된다
                    .andExpect(jsonPath("$[0].addressRoad").value("대구광역시 북구 대학로 80"))
                    .andExpect(jsonPath("$[0].bizNo").value("1234567890"))
                    .andExpect(jsonPath("$[0].phone").value("053-111-1111"))
                    .andExpect(jsonPath("$[0].internalStatus").value("OPEN"))
                    .andExpect(jsonPath("$[0].lat").value(35.89))
                    .andExpect(jsonPath("$[0].lng").value(128.61))
                    .andRespond(withSuccess(DOC_SAMPLE, MediaType.APPLICATION_JSON));

            client.investigate(TARGET);

            server.verify();
        }

        @Test
        @DisplayName("문서 예시 응답을 판정·수정안·근거로 옮긴다 — 수정안 키는 ChangeField 로 바뀐다")
        void mapsDocSample() {
            respond(DOC_SAMPLE);

            AiFinding finding = client.investigate(TARGET);

            assertThat(finding.storeId()).isEqualTo(44L);
            assertThat(finding.isFailed()).isFalse();
            assertThat(finding.classification()).isEqualTo(TaskClassification.PRIORITY_CHECK);
            assertThat(finding.proposedChanges()).containsExactly(Map.entry(ChangeField.PHONE, "053-964-0022"));
            assertThat(finding.signals()).containsExactly(new AiSignal(
                    SignalType.SIGNAL_HIGH, ChangeField.PHONE, "053-964-0022",
                    "전화번호: 전화번호 053-964-0022", "https://example.com/a"));
        }

        @Test
        @DisplayName("변화가 없으면 수정안·근거가 비어 있다")
        void noChange() {
            respond(result("""
                    {"storeId": 44, "classification": "NO_CHANGE", "proposedChanges": {}, "signals": [], "failure": null}
                    """));

            AiFinding finding = client.investigate(TARGET);

            assertThat(finding.classification()).isEqualTo(TaskClassification.NO_CHANGE);
            assertThat(finding.proposedChanges()).isEmpty();
            assertThat(finding.signals()).isEmpty();
        }

        @Test
        @DisplayName("수정안·근거 칸이 아예 없어도 빈 것으로 본다")
        void missingCollectionsAreEmpty() {
            respond(result("{\"storeId\": 44, \"classification\": \"NO_CHANGE\"}"));

            AiFinding finding = client.investigate(TARGET);

            assertThat(finding.proposedChanges()).isEmpty();
            assertThat(finding.signals()).isEmpty();
        }

        @ParameterizedTest
        @EnumSource(ChangeField.class)
        @DisplayName("모든 항목 키를 알아본다")
        void mapsEveryFieldKey(ChangeField field) {
            respond(result("""
                    {"storeId": 44, "classification": "PRIORITY_CHECK",
                     "proposedChanges": {"%1$s": "새 값"},
                     "signals": [{"signalType": "SIGNAL_HIGH", "field": "%1$s", "observed": "새 값",
                                  "evidenceText": "근거", "evidenceUrl": null}]}
                    """.formatted(field.key())));

            AiFinding finding = client.investigate(TARGET);

            assertThat(finding.proposedChanges()).containsOnlyKeys(field);
            assertThat(finding.signals()).extracting(AiSignal::field).containsExactly(field);
        }

        @Test
        @DisplayName("AI 가 그 가게를 실패로 답하면(200 + failure) 예외가 아니라 실패 결과다 — 다시 부르면 또 60초를 쓴다")
        void failureIsResultNotException() {
            // ai/src/investigation/mock.py 가 근거를 못 찾았을 때 돌려주는 모양
            respond(result("{\"storeId\": 44, \"failure\": \"근거를 찾지 못했습니다\", \"classification\": null}"));

            AiFinding finding = client.investigate(TARGET);

            assertThat(finding.isFailed()).isTrue();
            assertThat(finding.failure()).isEqualTo("근거를 찾지 못했습니다");
            assertThat(finding.classification()).isNull();
        }
    }

    @Nested
    @DisplayName("HTTP 상태코드")
    class HttpStatusCodes {

        private void respondWith(HttpStatus status) {
            server.expect(requestTo(INVESTIGATIONS_URL)).andRespond(withStatus(status));
        }

        @Test
        @DisplayName("503 은 조사 구현이 없다는 뜻이라 AiUnavailable — 남은 가게도 다 같은 실패라 Job 을 멈춘다")
        void unavailable() {
            respondWith(HttpStatus.SERVICE_UNAVAILABLE);

            assertThatThrownBy(() -> client.investigate(TARGET)).isInstanceOf(AiUnavailable.class);
        }

        @Test
        @DisplayName("429 는 사용량 한도라 AiQuotaExceeded")
        void quotaExceeded() {
            respondWith(HttpStatus.TOO_MANY_REQUESTS);

            assertThatThrownBy(() -> client.investigate(TARGET)).isInstanceOf(AiQuotaExceeded.class);
        }

        @ParameterizedTest
        @ValueSource(ints = {500, 502, 504})
        @DisplayName("그 밖의 5xx 는 잠깐의 장애일 수 있어 AiTransientError — 재시도 대상")
        void serverErrorIsTransient(int status) {
            respondWith(HttpStatus.valueOf(status));

            assertThatThrownBy(() -> client.investigate(TARGET)).isInstanceOf(AiTransientError.class);
        }

        @Test
        @DisplayName("거절 이유(응답 본문)를 예외 메시지에 담는다 — 로그에서 원인을 알 수 있게")
        void keepsErrorBodyInMessage() {
            server.expect(requestTo(INVESTIGATIONS_URL)).andRespond(withStatus(HttpStatus.UNPROCESSABLE_ENTITY)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{\"detail\": \"List should have at most 100 items\"}"));

            assertThatThrownBy(() -> client.investigate(TARGET))
                    .isInstanceOf(AiContractError.class)
                    .hasMessageContaining("HTTP 422")
                    .hasMessageContaining("at most 100 items");
        }

        @ParameterizedTest
        @ValueSource(ints = {400, 404, 422})
        @DisplayName("그 밖의 4xx 는 다시 보내도 같아서 AiContractError")
        void clientErrorIsContract(int status) {
            respondWith(HttpStatus.valueOf(status));

            assertThatThrownBy(() -> client.investigate(TARGET)).isInstanceOf(AiContractError.class);
        }
    }

    @Nested
    @DisplayName("약속과 다른 응답은 AiContractError")
    class ContractViolations {

        private void assertContractError() {
            assertThatThrownBy(() -> client.investigate(TARGET)).isInstanceOf(AiContractError.class);
        }

        @Test
        @DisplayName("실패도 판정도 없음 — mock.py 의 성공 응답이 이 모양이다")
        void neitherFailureNorClassification() {
            respond(result("{\"storeId\": 44, \"evidences\": [{\"source\": \"mock\", \"detail\": \"영업중\"}]}"));

            assertContractError();
        }

        @Test
        @DisplayName("모르는 판정 값")
        void unknownClassification() {
            respond(result("{\"storeId\": 44, \"classification\": \"MAYBE\"}"));

            assertContractError();
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "{\"results\": [], \"requested\": 1, \"succeeded\": 0}",
                "{\"requested\": 1, \"succeeded\": 0}"})
        @DisplayName("결과가 없음 — 요청 수와 결과 수가 같다는 AI 약속이 깨졌다")
        void noResult(String json) {
            respond(json);

            assertContractError();
        }

        @Test
        @DisplayName("결과가 두 건")
        void twoResults() {
            respond("""
                    {"results": [{"storeId": 44, "classification": "NO_CHANGE"},
                                 {"storeId": 44, "classification": "NO_CHANGE"}], "requested": 1, "succeeded": 2}
                    """);

            assertContractError();
        }

        @Test
        @DisplayName("결과 목록에 빈 항목(null) — NPE 로 Job 전체를 멈추지 않고 그 가게만 실패로")
        void nullResult() {
            respond("{\"results\": [null], \"requested\": 1, \"succeeded\": 0}");

            assertContractError();
        }

        @Test
        @DisplayName("근거 목록에 빈 항목(null)")
        void nullSignal() {
            respond(result("{\"storeId\": 44, \"classification\": \"PRIORITY_CHECK\", \"signals\": [null]}"));

            assertContractError();
        }

        @Test
        @DisplayName("다른 가게의 결과 — 다른 가게에 수정안이 붙으면 안 된다")
        void otherStoreResult() {
            respond(result("{\"storeId\": 45, \"classification\": \"NO_CHANGE\"}"));

            assertContractError();
        }

        @Test
        @DisplayName("수정안에 모르는 항목 키")
        void unknownProposedChangeKey() {
            respond(result("{\"storeId\": 44, \"classification\": \"PRIORITY_CHECK\", \"proposedChanges\": {\"hours\": \"09-18\"}}"));

            assertContractError();
        }

        @Test
        @DisplayName("수정안의 값이 비어 있음(null)")
        void nullProposedChangeValue() {
            respond(result("{\"storeId\": 44, \"classification\": \"PRIORITY_CHECK\", \"proposedChanges\": {\"phone\": null}}"));

            assertContractError();
        }

        @Test
        @DisplayName("근거에 항목(field)이 없음 — 백엔드 Signal.field 는 필수다")
        void signalWithoutField() {
            respond(result("""
                    {"storeId": 44, "classification": "PRIORITY_CHECK", "proposedChanges": {"phone": "053"},
                     "signals": [{"signalType": "SIGNAL_HIGH", "field": null, "evidenceText": "근거"}]}
                    """));

            assertContractError();
        }

        @Test
        @DisplayName("근거에 모르는 신호 등급")
        void unknownSignalType() {
            respond(result("""
                    {"storeId": 44, "classification": "PRIORITY_CHECK", "proposedChanges": {"phone": "053"},
                     "signals": [{"signalType": "SIGNAL_MAX", "field": "phone", "evidenceText": "근거"}]}
                    """));

            assertContractError();
        }

        @Test
        @DisplayName("JSON 이 깨짐")
        void brokenJson() {
            respond("{\"results\": [");

            assertContractError();
        }

        @Test
        @DisplayName("본문이 비어 있음")
        void emptyBody() {
            server.expect(requestTo(INVESTIGATIONS_URL)).andRespond(withSuccess());

            assertContractError();
        }
    }

    /**
     * 실제 소켓으로 확인한다. JDK HttpClient 는 읽기 타임아웃을 {@code HttpTimeoutException},
     * 연결 실패를 {@code ConnectException} 으로 던지는데, 둘 다 {@code ResourceAccessException} 에 싸여 온다.
     */
    @Nested
    @DisplayName("네트워크")
    class Network {

        private HttpServer slowServer;
        private final CountDownLatch release = new CountDownLatch(1);

        @AfterEach
        void stopServer() {
            release.countDown();
            if (slowServer != null) {
                slowServer.stop(0);
            }
        }

        @Test
        @DisplayName("읽기 타임아웃 안에 답이 없으면 AiReadTimeout — 재시도하지 않을 실패")
        void readTimeout() throws IOException {
            slowServer = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            slowServer.createContext("/investigations", exchange -> {
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                exchange.sendResponseHeaders(200, -1);
                exchange.close();
            });
            slowServer.start();
            AiApiClient realClient = new AiApiClient(RestClient.builder(),
                    "http://localhost:" + slowServer.getAddress().getPort(), 1_000, 200);

            assertThatThrownBy(() -> realClient.investigate(TARGET)).isInstanceOf(AiReadTimeout.class);
        }

        @Test
        @DisplayName("AI 서버가 꺼져 있어 연결이 안 되면 AiTransientError — 재시도 대상")
        void connectionRefused() throws IOException {
            int closedPort;
            try (ServerSocket socket = new ServerSocket(0)) {
                closedPort = socket.getLocalPort();
            }
            AiApiClient realClient = new AiApiClient(RestClient.builder(),
                    "http://localhost:" + closedPort, 1_000, 1_000);

            assertThatThrownBy(() -> realClient.investigate(TARGET)).isInstanceOf(AiTransientError.class);
        }
    }
}
