package com.ktc4.backend.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ktc4.backend.domain.store.entity.Store;
import com.ktc4.backend.domain.store.enums.StoreStatus;
import com.ktc4.backend.domain.store.ntscheck.scheduler.NtsCheckScheduler;
import com.ktc4.backend.domain.store.repository.StoreRepository;
import com.ktc4.backend.domain.store.util.StoreNormalizer;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 국세청 대조 흐름이 끝에서 끝까지 이어지는지 확인하는 통합 테스트.
 *
 * <p>각 조각은 단위 테스트가 따로 검증하지만, 조각끼리 실제로 맞물리는지(빈 주입, HTTP 직렬화,
 * DB 저장, 조회 쿼리)는 어디서도 확인하지 않았다. 여기서는 아래 경로를 한 번에 돌린다.
 * <pre>
 * NtsCheckScheduler → BusinessLookupService → NtsApiClient ─HTTP→ (가짜 국세청)
 *   → StoreNtsCheckService → store_nts_check / store_nts_change
 *   → GET /api/stores/nts-checks → StoreService → StoreRepository → StoreCheckResponse
 * </pre>
 *
 * <p>국세청만 가짜로 바꾼다. {@code NtsClient} 를 목으로 바꾸면 {@code NtsApiClient} 의 요청 형식과
 * 응답 해석이 흐름에서 빠지므로, JDK 내장 HTTP 서버로 실제 HTTP 요청을 받는다.
 *
 * <p>{@code @SpringBootTest} 는 테스트가 끝나도 롤백하지 않는다. 같은 Postgres 컨테이너를 쓰는
 * 다른 Repository 테스트가 빈 테이블을 전제하므로, 테스트 앞뒤로 테이블을 비운다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("국세청 대조 흐름 통합")
class NtsCheckFlowIntegrationTest {

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = PostgresContainerTest.POSTGRES;

    private static final String SERVICE_KEY = "integration-test-key";
    private static final ObjectMapper JSON = new ObjectMapper();

    // 가짜 국세청이 받은 요청과, 번호별로 돌려줄 상태. 테스트마다 초기화한다.
    private static final List<List<String>> receivedBizNos = new CopyOnWriteArrayList<>();
    private static final List<String> receivedQueries = new CopyOnWriteArrayList<>();
    private static final Map<String, NtsAnswer> ntsAnswers = new ConcurrentHashMap<>();
    private static volatile boolean ntsDown;

    private static final HttpServer NTS_STUB = startNtsStub();

    @DynamicPropertySource
    static void pointNtsToStub(DynamicPropertyRegistry registry) {
        registry.add("external.nts.base-url",
                () -> "http://localhost:" + NTS_STUB.getAddress().getPort() + "/status");
        registry.add("external.nts.service-key", () -> SERVICE_KEY);
    }

    @AfterAll
    static void stopNtsStub() {
        NTS_STUB.stop(0);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private NtsCheckScheduler ntsCheckScheduler;

    @Autowired
    private StoreRepository storeRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        clearTables();
        receivedBizNos.clear();
        receivedQueries.clear();
        ntsAnswers.clear();
        ntsDown = false;
    }

    @AfterEach
    void tearDown() {
        clearTables();
    }

    @Test
    @DisplayName("배치가 국세청을 조회해 저장하고, 조회 API 가 그 결과를 필터별로 내려준다")
    void batchResultFlowsToApi() throws Exception {
        // 같은 번호를 다른 표기로 쓰는 두 가게, 폐업 불일치, 번호 없음, 국세청 미등록
        Long match = saveStore("정상 가게", StoreStatus.OPEN, "123-45-67890");
        Long sameBizNo = saveStore("정상 가게 2호점", StoreStatus.OPEN, "1234567890");
        Long closed = saveStore("폐업 가게", StoreStatus.OPEN, "2345678901");
        Long noBizNo = saveStore("번호 없는 가게", StoreStatus.OPEN, null);
        Long unregistered = saveStore("미등록 가게", StoreStatus.OPEN, "3456789012");

        ntsAnswers.put("1234567890", NtsAnswer.active());
        ntsAnswers.put("2345678901", NtsAnswer.closed("20260801"));
        // 3456789012 는 답을 넣지 않는다 — 가짜 국세청이 미등록("")으로 돌려준다

        // 배치 전: 확인 기록이 없으니 전부 UNCONFIRMED
        Map<Long, JsonNode> before = fetchNtsChecks(null);
        assertThat(before).hasSize(5);
        assertThat(before.values()).allSatisfy(row ->
                assertThat(row.get("ntsLookup").asText()).isEqualTo("UNCONFIRMED"));

        ntsCheckScheduler.checkAllStores();

        // 국세청 요청: 번호가 정규화·중복 제거되어 한 번에 나가고, 서비스키가 실린다
        assertThat(receivedBizNos).hasSize(1);
        assertThat(receivedBizNos.get(0))
                .containsExactlyInAnyOrder("1234567890", "2345678901", "3456789012");
        assertThat(receivedQueries.get(0)).contains("serviceKey=" + SERVICE_KEY);

        Map<Long, JsonNode> all = fetchNtsChecks(null);

        assertRow(all.get(match), "CONFIRMED", "ACTIVE", "MATCH", false, false);
        assertRow(all.get(sameBizNo), "CONFIRMED", "ACTIVE", "MATCH", false, false);
        assertRow(all.get(closed), "CONFIRMED", "CLOSED", "OPEN_BUT_CLOSED", true, false);
        assertThat(all.get(closed).get("ntsClosedAt").asText()).isEqualTo("2026-08-01");
        assertRow(all.get(noBizNo), "NO_BIZ_NO", null, "NOT_COMPARABLE", false, true);
        assertRow(all.get(unregistered), "CONFIRMED", "NOT_REGISTERED", "NTS_NOT_REGISTERED", false, true);

        assertThat(all.get(match).get("ntsCheckedAt").isNull()).isFalse();
        assertThat(all.get(noBizNo).get("ntsCheckedAt").isNull()).isTrue();

        // 필터: 상태 불일치는 AI 조사 대상, 데이터 문제는 번호부터 찾을 대상
        assertThat(fetchNtsChecks("STATUS_MISMATCH").keySet()).containsExactly(closed);
        assertThat(fetchNtsChecks("DATA_PROBLEM").keySet()).containsExactly(noBizNo, unregistered);

        // 변경 이력: 처음 확인된 가게마다 "없음 → 현재 상태" 한 줄. 번호 없는 가게는 남지 않는다
        assertThat(changeHistory()).containsExactlyInAnyOrder(
                change(match, null, "ACTIVE"),
                change(sameBizNo, null, "ACTIVE"),
                change(closed, null, "CLOSED"),
                change(unregistered, null, "NOT_REGISTERED"));
    }

    @Test
    @DisplayName("국세청이 실패하면 UNCONFIRMED 로 남되, 이전에 확인한 상태와 확인 시각은 그대로 내려간다")
    void keepsPreviousResultWhenNtsFails() throws Exception {
        Long closed = saveStore("폐업 가게", StoreStatus.OPEN, "2345678901");
        ntsAnswers.put("2345678901", NtsAnswer.closed("20260801"));

        ntsCheckScheduler.checkAllStores();
        JsonNode first = fetchNtsChecks(null).get(closed);

        ntsDown = true;
        ntsCheckScheduler.checkAllStores();
        JsonNode second = fetchNtsChecks(null).get(closed);

        assertThat(receivedBizNos).hasSize(2);   // 두 번째 회차도 실제로 조회를 시도했다
        assertRow(second, "UNCONFIRMED", "CLOSED", "OPEN_BUT_CLOSED", true, false);
        assertThat(second.get("ntsCheckedAt")).isEqualTo(first.get("ntsCheckedAt"));
        assertThat(fetchNtsChecks("STATUS_MISMATCH").keySet()).containsExactly(closed);
        assertThat(changeHistory()).containsExactly(change(closed, null, "CLOSED"));
    }

    @Test
    @DisplayName("다음 회차에 국세청 상태가 바뀌면 조회 결과가 갱신되고 변경 이력이 한 줄 더 쌓인다")
    void recordsStateChangeOnNextRun() throws Exception {
        Long store = saveStore("정상 가게", StoreStatus.OPEN, "1234567890");
        ntsAnswers.put("1234567890", NtsAnswer.active());

        ntsCheckScheduler.checkAllStores();
        assertThat(fetchNtsChecks("STATUS_MISMATCH")).isEmpty();

        ntsAnswers.put("1234567890", NtsAnswer.closed("20260920"));
        ntsCheckScheduler.checkAllStores();

        JsonNode row = fetchNtsChecks(null).get(store);
        assertRow(row, "CONFIRMED", "CLOSED", "OPEN_BUT_CLOSED", true, false);
        assertThat(row.get("ntsClosedAt").asText()).isEqualTo("2026-09-20");
        assertThat(fetchNtsChecks("STATUS_MISMATCH").keySet()).containsExactly(store);
        assertThat(changeHistory()).containsExactlyInAnyOrder(
                change(store, null, "ACTIVE"),
                change(store, "ACTIVE", "CLOSED"));
    }

    // ── 도우미 ──────────────────────────────────────────────────────────

    private Long saveStore(String name, StoreStatus status, String bizNo) {
        String address = "가상특별시 예시구 샘플로 123";
        Store store = Store.builder()
                .name(name)
                .nameNormalized(StoreNormalizer.normalizeName(name))
                .addressRoad(address)
                .addressNormalized(StoreNormalizer.normalizeAddress(address))
                .status(status)
                .bizNo(bizNo)
                .build();
        return storeRepository.save(store).getStoreId();
    }

    // 조회 API 응답의 content 를 storeId 순서 그대로 담는다 (API 가 storeId 오름차순을 보장한다)
    private Map<Long, JsonNode> fetchNtsChecks(String filter) throws Exception {
        var request = get("/api/stores/nts-checks").param("limit", "100");
        if (filter != null) {
            request = request.param("filter", filter);
        }
        String body = mockMvc.perform(request)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        Map<Long, JsonNode> rows = new LinkedHashMap<>();
        for (JsonNode row : JSON.readTree(body).get("content")) {
            rows.put(row.get("storeId").asLong(), row);
        }
        return rows;
    }

    private static void assertRow(JsonNode row, String ntsLookup, String ntsStatus, String comparison,
                                  boolean statusMismatch, boolean dataProblem) {
        assertThat(row.get("ntsLookup").asText()).isEqualTo(ntsLookup);
        if (ntsStatus == null) {
            assertThat(row.get("ntsStatus").isNull()).isTrue();
        } else {
            assertThat(row.get("ntsStatus").asText()).isEqualTo(ntsStatus);
        }
        assertThat(row.get("statusComparison").asText()).isEqualTo(comparison);
        assertThat(row.get("statusMismatch").asBoolean()).isEqualTo(statusMismatch);
        assertThat(row.get("dataProblem").asBoolean()).isEqualTo(dataProblem);
    }

    private List<String> changeHistory() {
        return jdbcTemplate.query(
                "select store_id, from_state, to_state from store_nts_change",
                (rs, i) -> change(rs.getLong("store_id"), rs.getString("from_state"), rs.getString("to_state")));
    }

    private static String change(Long storeId, String from, String to) {
        return storeId + ":" + from + "->" + to;
    }

    // store 를 참조하는 확인 기록·변경 이력(및 task·verification)까지 함께 비운다
    private void clearTables() {
        jdbcTemplate.execute("TRUNCATE TABLE store RESTART IDENTITY CASCADE");
    }

    // ── 가짜 국세청 ────────────────────────────────────────────────────

    private record NtsAnswer(String code, String endDt) {
        static NtsAnswer active() {
            return new NtsAnswer("01", "");
        }

        static NtsAnswer closed(String endDt) {
            return new NtsAnswer("03", endDt);
        }
    }

    private static HttpServer startNtsStub() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/status", NtsCheckFlowIntegrationTest::handleNtsRequest);
            server.start();
            return server;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // 실제 국세청 응답 모양을 따른다: 매칭 안 된 번호도 빠지지 않고 b_stt_cd 가 "" 로 온다
    private static void handleNtsRequest(HttpExchange exchange) throws IOException {
        JsonNode request = JSON.readTree(exchange.getRequestBody());
        List<String> bizNos = new ArrayList<>();
        request.get("b_no").forEach(node -> bizNos.add(node.asText()));
        receivedBizNos.add(bizNos);
        receivedQueries.add(exchange.getRequestURI().getRawQuery());

        if (ntsDown) {
            respond(exchange, 500, "{\"status_code\":\"INTERNAL_ERROR\"}");
            return;
        }

        ObjectNode response = JSON.createObjectNode();
        response.put("request_cnt", bizNos.size());
        response.put("status_code", "OK");
        ArrayNode data = response.putArray("data");
        for (String bizNo : bizNos) {
            NtsAnswer answer = ntsAnswers.get(bizNo);
            ObjectNode item = data.addObject();
            item.put("b_no", bizNo);
            item.put("b_stt_cd", answer == null ? "" : answer.code());
            item.put("end_dt", answer == null ? "" : answer.endDt());
            item.put("tax_type", answer == null ? "국세청에 등록되지 않은 사업자등록번호입니다." : "부가가치세 일반과세자");
        }
        respond(exchange, 200, JSON.writeValueAsString(response));
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
