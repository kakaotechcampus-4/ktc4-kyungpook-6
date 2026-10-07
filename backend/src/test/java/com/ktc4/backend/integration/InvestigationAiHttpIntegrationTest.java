package com.ktc4.backend.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ktc4.backend.domain.business.enums.BusinessState;
import com.ktc4.backend.domain.job.entity.Job;
import com.ktc4.backend.domain.job.repository.JobRepository;
import com.ktc4.backend.domain.member.enums.MemberRole;
import com.ktc4.backend.domain.store.entity.Store;
import com.ktc4.backend.domain.store.enums.NtsLookupResult;
import com.ktc4.backend.domain.store.enums.StoreStatus;
import com.ktc4.backend.domain.store.ntscheck.entity.StoreNtsCheck;
import com.ktc4.backend.domain.store.ntscheck.repository.StoreNtsCheckRepository;
import com.ktc4.backend.domain.store.repository.StoreRepository;
import com.ktc4.backend.global.security.JwtProvider;
import com.ktc4.backend.support.PostgresContainerTest;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AI 서버와 실제 HTTP 로 주고받는 조사 흐름 통합.
 * <pre>
 * POST /api/jobs → InvestigationRunner(@Async) → AiApiClient ─HTTP→ (가짜 AI 서버)
 *   → TaskService → task / signal → GET /api/jobs/{id}
 * </pre>
 *
 * <p>{@link InvestigationFlowIntegrationTest} 는 {@code AiClient} 자체를 가짜로 바꿔 비동기·트랜잭션을 본다. 여기서는
 * {@code AiApiClient} 를 그대로 두고 AI 서버만 JDK 내장 HTTP 서버로 바꿔, 요청 형식·응답 해석·저장·조회가 한 번에
 * 이어지는지 본다. 응답은 AI 연동 문서({@code ai/docs/백엔드_연동.md})의 예시 모양 그대로다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("AI HTTP 조사 흐름 통합")
class InvestigationAiHttpIntegrationTest {

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = PostgresContainerTest.POSTGRES;

    private static final Duration WAIT = Duration.ofSeconds(10);
    private static final ObjectMapper JSON = new ObjectMapper();

    // 가게 ID 별로 돌려줄 응답(상태코드, 본문)을 차례로 쌓는다. 비어 있으면 변화 없음으로 답한다.
    private static final Map<Long, Queue<AiAnswer>> answers = new ConcurrentHashMap<>();
    private static final List<JsonNode> receivedBodies = new CopyOnWriteArrayList<>();

    private static final HttpServer AI_STUB = startAiStub();

    @DynamicPropertySource
    static void pointAiToStub(DynamicPropertyRegistry registry) {
        registry.add("ai.base-url", () -> "http://localhost:" + AI_STUB.getAddress().getPort());
        registry.add("ai.retry-backoff-ms", () -> "0");
        registry.add("auth.enforce", () -> "true");
        // 이 테스트만의 컨텍스트라 DB 연결 풀을 작게 잡는다 — 캐시된 컨텍스트마다 풀을 따로 잡아 Postgres 연결 한도를 넘지 않게
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "3");
    }

    @AfterAll
    static void stopAiStub() {
        AI_STUB.stop(0);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtProvider jwtProvider;

    @Autowired
    private StoreRepository storeRepository;

    @Autowired
    private StoreNtsCheckRepository storeNtsCheckRepository;

    @Autowired
    private JobRepository jobRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @BeforeEach
    void setUp() {
        clearTables();
        answers.clear();
        receivedBodies.clear();
    }

    @AfterEach
    void tearDown() {
        await().atMost(WAIT).until(() -> jobRepository.findAll().stream().allMatch(Job::isFinished));
        clearTables();
    }

    @Test
    @DisplayName("AI 가 HTTP 로 보낸 수정안·근거가 저장되어 조회 API 로 그대로 나온다")
    void savesAiResultFromHttp() throws Exception {
        Long closedByNts = store("폐업가게", BusinessState.CLOSED);
        Long changed = store("전화바뀐가게", BusinessState.ACTIVE);
        Long unchanged = store("그대로가게", BusinessState.ACTIVE);
        answer(changed, 200, """
                {"results": [{
                  "storeId": %d, "classification": "PRIORITY_CHECK",
                  "proposedChanges": {"phone": "053-964-0022"},
                  "signals": [{"signalType": "SIGNAL_HIGH", "field": "phone", "observed": "053-964-0022",
                               "evidenceText": "전화번호: 053-964-0022", "evidenceUrl": "https://example.com/a",
                               "sourceCount": 2}],
                  "mapCheck": {"status": "FOUND", "placeUrl": "http://place.map.kakao.com/1"},
                  "evidences": [], "failure": null
                }], "requested": 1, "succeeded": 1}
                """.formatted(changed));

        long jobId = startJob(closedByNts, changed, unchanged);
        JsonNode done = awaitStatus(jobId, "DONE");

        assertThat(done.path("completedCount").asInt()).isEqualTo(3);
        JsonNode nts = taskOf(done, closedByNts);
        assertThat(nts.path("classification").asText()).isEqualTo("PRIORITY_CHECK");
        assertThat(nts.path("proposedChanges").get(0).path("field").asText()).isEqualTo("status");
        assertThat(nts.path("proposedChanges").get(0).path("value").asText()).isEqualTo("CLOSED");
        assertThat(nts.path("evidences").get(0).path("source").asText()).isEqualTo("NTS");

        JsonNode ai = taskOf(done, changed);
        assertThat(ai.path("classification").asText()).isEqualTo("PRIORITY_CHECK");
        assertThat(ai.path("proposedChanges").get(0).path("field").asText()).isEqualTo("phone");
        assertThat(ai.path("proposedChanges").get(0).path("value").asText()).isEqualTo("053-964-0022");
        JsonNode evidence = ai.path("evidences").get(0);
        assertThat(evidence.path("field").asText()).isEqualTo("phone");
        assertThat(evidence.path("source").asText()).isEqualTo("AI_WEB");
        assertThat(evidence.path("description").asText()).isEqualTo("전화번호: 053-964-0022");
        assertThat(evidence.path("sourceUrl").asText()).isEqualTo("https://example.com/a");

        assertThat(taskOf(done, unchanged).path("classification").asText()).isEqualTo("NO_CHANGE");

        // 1차 대상은 AI 에 보내지 않고, AI 대상은 한 건짜리 목록으로 하나씩 보낸다
        assertThat(receivedBodies).hasSize(2);
        assertThat(receivedBodies).allSatisfy(body -> assertThat(body.size()).isEqualTo(1));
        assertThat(receivedBodies).extracting(body -> body.get(0).path("storeId").asLong())
                .containsExactlyInAnyOrder(changed, unchanged)
                .doesNotContain(closedByNts);
        assertThat(receivedBodies.get(0).get(0).path("addressRoad").asText()).isEqualTo("가상특별시 예시구 샘플로 123");
    }

    @Test
    @DisplayName("AI 가 그 가게를 실패로 답하면(200 + failure) 실패로 남기고 조사는 완료된다")
    void recordsAiReportedFailure() throws Exception {
        Long store = store("못찾은가게", BusinessState.ACTIVE);
        answer(store, 200, """
                {"results": [{"storeId": %d, "classification": null, "failure": "근거를 찾지 못했습니다"}],
                 "requested": 1, "succeeded": 0}
                """.formatted(store));

        long jobId = startJob(store);
        JsonNode done = awaitStatus(jobId, "DONE");

        JsonNode task = taskOf(done, store);
        assertThat(task.path("classification").isNull()).isTrue();
        assertThat(task.path("failureReason").asText()).isEqualTo("AI 가 이 가게를 조사하지 못했습니다");
        assertThat(receivedBodies).hasSize(1);
    }

    @Test
    @DisplayName("AI 가 잠깐 502 를 주면 한 번 더 불러 결과를 저장한다")
    void retriesTransientHttpError() throws Exception {
        Long store = store("재시도가게", BusinessState.ACTIVE);
        answer(store, 502, "{\"detail\": \"bad gateway\"}");

        long jobId = startJob(store);
        JsonNode done = awaitStatus(jobId, "DONE");

        assertThat(receivedBodies).hasSize(2);
        assertThat(taskOf(done, store).path("classification").asText()).isEqualTo("NO_CHANGE");
    }

    @Test
    @DisplayName("AI 가 503 이면 남은 가게를 부르지 않고 멈추지만, 먼저 저장한 1차 결과는 남는다")
    void stopsOnUnavailable() throws Exception {
        Long closedByNts = store("폐업가게", BusinessState.CLOSED);
        Long first = store("정상가게1", BusinessState.ACTIVE);
        Long second = store("정상가게2", BusinessState.ACTIVE);
        answer(first, 503, "{\"detail\": \"조사 구현이 없습니다\"}");
        answer(second, 503, "{\"detail\": \"조사 구현이 없습니다\"}");

        long jobId = startJob(closedByNts, first, second);
        JsonNode failed = awaitStatus(jobId, "FAILED");

        assertThat(failed.path("errorMessage").asText()).isEqualTo("AI 조사를 쓸 수 없어 조사를 멈췄습니다");
        assertThat(failed.path("completedCount").asInt()).isEqualTo(1);
        assertThat(failed.path("tasks")).hasSize(1);
        assertThat(failed.path("tasks").get(0).path("storeId").asLong()).isEqualTo(closedByNts);
        assertThat(receivedBodies).hasSize(1);
    }

    // ── 도우미 ──────────────────────────────────────────────────────

    private void answer(Long storeId, int status, String body) {
        answers.computeIfAbsent(storeId, id -> new ConcurrentLinkedQueue<>()).add(new AiAnswer(status, body));
    }

    private long startJob(Long... storeIds) throws Exception {
        String response = mockMvc.perform(post("/api/jobs")
                        .header(HttpHeaders.AUTHORIZATION, adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(Map.of("storeIds", List.of(storeIds)))))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        return JSON.readTree(response).path("jobId").asLong();
    }

    private JsonNode awaitStatus(long jobId, String expected) throws Exception {
        await().atMost(WAIT).until(() -> expected.equals(job(jobId).path("status").asText()));
        return job(jobId);
    }

    private JsonNode job(long jobId) throws Exception {
        String response = mockMvc.perform(get("/api/jobs/" + jobId).header(HttpHeaders.AUTHORIZATION, adminToken()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JSON.readTree(response);
    }

    private static JsonNode taskOf(JsonNode job, Long storeId) {
        for (JsonNode task : job.path("tasks")) {
            if (task.path("storeId").asLong() == storeId) {
                return task;
            }
        }
        throw new AssertionError("storeId=" + storeId + " 의 결과가 없습니다: " + job);
    }

    private String adminToken() {
        return "Bearer " + jwtProvider.issue(1L, MemberRole.ADMIN).value();
    }

    // 국세청 기록이 가게를 참조하므로 둘을 한 트랜잭션에서 저장한다
    private Long store(String name, BusinessState ntsState) {
        return transactionTemplate.execute(status -> {
            String bizNo = String.valueOf(1_000_000_000L + storeRepository.count());
            Store store = storeRepository.save(Store.builder()
                    .name(name).nameNormalized(name)
                    .addressRoad("가상특별시 예시구 샘플로 123").addressNormalized("가상특별시예시구샘플로123")
                    .status(StoreStatus.OPEN).bizNo(bizNo).lastCheckedAt(LocalDateTime.now())
                    .build());
            storeNtsCheckRepository.save(StoreNtsCheck.builder()
                    .store(store).bizNo(bizNo).checkResult(NtsLookupResult.CONFIRMED).ntsState(ntsState)
                    .lastAttemptAt(LocalDateTime.now()).lastSuccessAt(LocalDateTime.now())
                    .build());
            return store.getStoreId();
        });
    }

    private void clearTables() {
        jdbcTemplate.execute("TRUNCATE TABLE store, job RESTART IDENTITY CASCADE");
    }

    // ── 가짜 AI 서버 ───────────────────────────────────────────────

    private record AiAnswer(int status, String body) {
    }

    private static HttpServer startAiStub() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/investigations", InvestigationAiHttpIntegrationTest::handle);
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException("가짜 AI 서버를 띄우지 못했습니다", e);
        }
    }

    private static void handle(HttpExchange exchange) throws IOException {
        JsonNode body = JSON.readTree(exchange.getRequestBody());
        receivedBodies.add(body);
        long storeId = body.get(0).path("storeId").asLong();
        Queue<AiAnswer> queue = answers.get(storeId);
        AiAnswer answer = queue == null || queue.isEmpty() ? null : queue.poll();
        if (answer == null) {
            answer = new AiAnswer(200, """
                    {"results": [{"storeId": %d, "classification": "NO_CHANGE", "proposedChanges": {},
                                  "signals": [], "failure": null}], "requested": 1, "succeeded": 1}
                    """.formatted(storeId));
        }
        byte[] bytes = answer.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(answer.status(), bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
