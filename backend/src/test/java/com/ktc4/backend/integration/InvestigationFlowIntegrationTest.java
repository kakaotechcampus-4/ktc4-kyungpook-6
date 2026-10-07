package com.ktc4.backend.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ktc4.backend.domain.business.enums.BusinessState;
import com.ktc4.backend.domain.investigation.client.AiClient;
import com.ktc4.backend.domain.investigation.dto.AiFinding;
import com.ktc4.backend.domain.investigation.dto.AiSignal;
import com.ktc4.backend.domain.investigation.dto.InvestigationTarget;
import com.ktc4.backend.domain.job.entity.Job;
import com.ktc4.backend.domain.job.enums.JobStatus;
import com.ktc4.backend.domain.job.repository.JobRepository;
import com.ktc4.backend.domain.member.enums.MemberRole;
import com.ktc4.backend.domain.signal.enums.ChangeField;
import com.ktc4.backend.domain.signal.enums.SignalType;
import com.ktc4.backend.domain.signal.repository.SignalRepository;
import com.ktc4.backend.domain.store.entity.Store;
import com.ktc4.backend.domain.store.enums.NtsLookupResult;
import com.ktc4.backend.domain.store.enums.StoreStatus;
import com.ktc4.backend.domain.store.ntscheck.entity.StoreNtsCheck;
import com.ktc4.backend.domain.store.ntscheck.repository.StoreNtsCheckRepository;
import com.ktc4.backend.domain.store.repository.StoreRepository;
import com.ktc4.backend.domain.task.entity.Task;
import com.ktc4.backend.domain.task.enums.TaskClassification;
import com.ktc4.backend.domain.task.repository.TaskRepository;
import com.ktc4.backend.global.security.JwtProvider;
import com.ktc4.backend.support.PostgresContainerTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 관리자 조사 흐름 통합 — 실제 {@code @Async} 스레드·트랜잭션·DB 로 돌린다.
 * <pre>
 * POST /api/jobs → JobService(커밋) → InvestigationRunner(@Async, investigation- 스레드)
 *   → TaskService(가게마다 커밋) → GET /api/jobs/{id} 로 진행도 확인
 * </pre>
 *
 * <p>AI 만 가짜로 바꾼다. 가짜는 허락(permit)을 받아야 답하므로, 조사 도중의 상태를 멈춰 놓고 볼 수 있다.
 * 기다리는 건 {@code Thread.sleep} 이 아니라 Awaitility 로 조건이 될 때까지다.
 *
 * <p>테스트에 {@code @Transactional} 을 붙이지 않는다 — 붙이면 실행기 스레드가 테스트 트랜잭션의 데이터를 보지 못한다.
 * 그래서 테스트 앞뒤로 테이블을 비우고, 끝날 때 실행기가 하던 조사를 모두 마치게 한 뒤 비운다
 * (안 그러면 다음 테스트가 앞 테스트의 조사와 섞인다).
 */
@SpringBootTest
@AutoConfigureMockMvc
// 이 테스트만의 컨텍스트라 DB 연결 풀을 작게 잡는다 — 캐시된 컨텍스트마다 풀을 따로 잡아 Postgres 연결 한도를 넘지 않게
@TestPropertySource(properties = {"auth.enforce=true", "ai.retry-backoff-ms=0", "spring.datasource.hikari.maximum-pool-size=3"})
@DisplayName("관리자 조사 흐름 통합")
class InvestigationFlowIntegrationTest {

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = PostgresContainerTest.POSTGRES;

    private static final Duration WAIT = Duration.ofSeconds(10);
    private static final ObjectMapper JSON = new ObjectMapper();

    @TestConfiguration
    static class FakeAiConfig {
        @Bean
        @Primary
        ControllableAiClient controllableAiClient() {
            return new ControllableAiClient();
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtProvider jwtProvider;

    @Autowired
    private ControllableAiClient fakeAi;

    @Autowired
    private StoreRepository storeRepository;

    @Autowired
    private StoreNtsCheckRepository storeNtsCheckRepository;

    @Autowired
    private JobRepository jobRepository;

    @Autowired
    private TaskRepository taskRepository;

    @Autowired
    private SignalRepository signalRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @BeforeEach
    void setUp() {
        clearTables();
        fakeAi.reset();
    }

    @AfterEach
    void tearDown() {
        // 실행기에 남은 조사가 다음 테스트 데이터와 섞이지 않게, 막아 둔 가짜 AI 를 풀고 모든 조사가 끝나길 기다린다
        fakeAi.releaseAll();
        await().atMost(WAIT).until(() -> jobRepository.findAll().stream().allMatch(Job::isFinished));
        clearTables();
    }

    @Test
    @DisplayName("202 가 먼저 오고, 1차 결과를 먼저 저장한 뒤 AI 결과를 가게마다 커밋하며 진행도가 오른다")
    void acceptsFirstAndCommitsPerStore() throws Exception {
        Long closedByNts = store("폐업가게", BusinessState.CLOSED);
        Long first = store("정상가게1", BusinessState.ACTIVE);
        Long second = store("정상가게2", BusinessState.ACTIVE);
        fakeAi.block();
        String requestThread = Thread.currentThread().getName();

        long jobId = startJob(closedByNts, first, second);

        // AI 가 아직 아무것도 답하지 않았는데 응답이 왔다. 1차 결과는 AI 없이 이미 커밋됐다
        await().atMost(WAIT).until(() -> job(jobId).path("completedCount").asInt() == 1);
        JsonNode inProgress = job(jobId);
        assertThat(inProgress.path("status").asText()).isEqualTo("IN_PROGRESS");
        assertThat(inProgress.path("tasks").get(0).path("storeId").asLong()).isEqualTo(closedByNts);

        fakeAi.release(1);
        await().atMost(WAIT).until(() -> job(jobId).path("completedCount").asInt() == 2);
        assertThat(job(jobId).path("status").asText()).isEqualTo("IN_PROGRESS");

        fakeAi.release(1);
        await().atMost(WAIT).until(() -> "DONE".equals(job(jobId).path("status").asText()));
        JsonNode done = job(jobId);
        assertThat(done.path("completedCount").asInt()).isEqualTo(3);
        assertThat(done.path("tasks")).hasSize(3);
        assertThat(fakeAi.threadNames()).isNotEmpty().doesNotContain(requestThread);
    }

    @Test
    @DisplayName("조사는 한 번에 하나만 돈다 — 나중 조사는 앞 조사가 끝날 때까지 대기한다")
    void runsOneJobAtATime() throws Exception {
        Long a = store("가게A", BusinessState.ACTIVE);
        Long b = store("가게B", BusinessState.ACTIVE);
        fakeAi.block();

        long firstJob = startJob(a);
        await().atMost(WAIT).until(() -> fakeAi.waiting() == 1);
        long secondJob = startJob(b);

        assertThat(job(secondJob).path("status").asText()).isEqualTo("PENDING");

        fakeAi.release(1);
        await().atMost(WAIT).until(() -> "DONE".equals(job(firstJob).path("status").asText()));
        await().atMost(WAIT).until(() -> fakeAi.waiting() == 1);
        assertThat(job(secondJob).path("status").asText()).isEqualTo("IN_PROGRESS");

        fakeAi.release(1);
        await().atMost(WAIT).until(() -> "DONE".equals(job(secondJob).path("status").asText()));
        assertThat(fakeAi.maxConcurrent()).isEqualTo(1);
    }

    @Test
    @DisplayName("예상 못 한 오류로 조사가 실패해도 실행기 스레드는 살아 다음 조사를 처리한다")
    void survivesUnexpectedError() throws Exception {
        Long broken = store("오류가게", BusinessState.ACTIVE);
        Long normal = store("정상가게", BusinessState.ACTIVE);
        fakeAi.answer(broken, target -> {
            throw new IllegalStateException("가짜 AI 의 버그");
        });

        long failedJob = startJob(broken);
        await().atMost(WAIT).until(() -> "FAILED".equals(job(failedJob).path("status").asText()));
        assertThat(job(failedJob).path("errorMessage").asText()).isEqualTo("서버 오류로 조사를 멈췄습니다");

        long nextJob = startJob(normal);
        await().atMost(WAIT).until(() -> "DONE".equals(job(nextJob).path("status").asText()));
    }

    @Test
    @DisplayName("결과 저장이 실패하면 그 트랜잭션이 통째로 롤백되고, 실패 기록 하나만 남아 진행 수가 한 번 오른다")
    void rollsBackFailedSave() throws Exception {
        Long store = store("긴값가게", BusinessState.ACTIVE);
        // observed 는 500자까지라 501자는 Signal INSERT 에서 실패한다 — Task 저장과 진행 수 +1 까지 함께 롤백돼야 한다
        fakeAi.answer(store, target -> AiFinding.success(target.storeId(),
                TaskClassification.PRIORITY_CHECK,
                Map.of(ChangeField.NAME, "새 상호"),
                List.of(new AiSignal(SignalType.SIGNAL_HIGH, ChangeField.NAME, "가".repeat(501), "상호", null))));

        long jobId = startJob(store);
        await().atMost(WAIT).until(() -> "DONE".equals(job(jobId).path("status").asText()));

        List<Task> tasks = taskRepository.findAll();
        assertThat(tasks).hasSize(1);
        assertThat(tasks.get(0).isFailed()).isTrue();
        assertThat(tasks.get(0).getFailureReason()).isEqualTo("조사 결과를 저장하지 못했습니다");
        assertThat(signalRepository.count()).isZero();
        assertThat(job(jobId).path("completedCount").asInt()).isEqualTo(1);
    }

    // ── 도우미 ──────────────────────────────────────────────────────

    private long startJob(Long... storeIds) throws Exception {
        String body = JSON.writeValueAsString(Map.of("storeIds", List.of(storeIds)));
        String response = mockMvc.perform(post("/api/jobs")
                        .header(HttpHeaders.AUTHORIZATION, adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        return JSON.readTree(response).path("jobId").asLong();
    }

    private JsonNode job(long jobId) throws Exception {
        String response = mockMvc.perform(get("/api/jobs/" + jobId).header(HttpHeaders.AUTHORIZATION, adminToken()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JSON.readTree(response);
    }

    private String adminToken() {
        return "Bearer " + jwtProvider.issue(1L, MemberRole.ADMIN).value();
    }

    // 국세청 기록이 가게를 참조하므로 둘을 한 트랜잭션에서 저장한다 (트랜잭션 밖의 가게는 detached 라 참조할 수 없다)
    private Long store(String name, BusinessState ntsState) {
        return transactionTemplate.execute(status -> saveStoreWithCheck(name, ntsState));
    }

    private Long saveStoreWithCheck(String name, BusinessState ntsState) {
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
    }

    private void clearTables() {
        jdbcTemplate.execute("TRUNCATE TABLE store, job RESTART IDENTITY CASCADE");
    }

    /**
     * 허락을 받아야 답하는 가짜 AI. 동시에 몇 건이 들어와 있는지와 어느 스레드에서 불렸는지 기록한다.
     */
    static class ControllableAiClient implements AiClient {

        private final Semaphore permits = new Semaphore(0);
        private final AtomicInteger inFlight = new AtomicInteger();
        private final AtomicInteger maxConcurrent = new AtomicInteger();
        private final AtomicInteger waiting = new AtomicInteger();
        private final Set<String> threadNames = ConcurrentHashMap.newKeySet();
        private final Map<Long, Function<InvestigationTarget, AiFinding>> answers = new ConcurrentHashMap<>();
        private volatile boolean blocking;

        @Override
        public AiFinding investigate(InvestigationTarget target) {
            maxConcurrent.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
            threadNames.add(Thread.currentThread().getName());
            try {
                if (blocking) {
                    waiting.incrementAndGet();
                    try {
                        permits.acquire();
                    } finally {
                        waiting.decrementAndGet();
                    }
                }
                return answers.getOrDefault(target.storeId(), ControllableAiClient::noChange).apply(target);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("가짜 AI 대기 중 인터럽트", e);
            } finally {
                inFlight.decrementAndGet();
            }
        }

        private static AiFinding noChange(InvestigationTarget target) {
            return AiFinding.success(target.storeId(),
                    TaskClassification.NO_CHANGE, Map.of(), List.of());
        }

        void block() {
            blocking = true;
        }

        void release(int count) {
            permits.release(count);
        }

        void releaseAll() {
            blocking = false;
            permits.release(1_000);
        }

        void answer(Long storeId, Function<InvestigationTarget, AiFinding> answer) {
            answers.put(storeId, answer);
        }

        int waiting() {
            return waiting.get();
        }

        int maxConcurrent() {
            return maxConcurrent.get();
        }

        Set<String> threadNames() {
            return Set.copyOf(threadNames);
        }

        void reset() {
            blocking = false;
            permits.drainPermits();
            maxConcurrent.set(0);
            threadNames.clear();
            answers.clear();
        }
    }
}
