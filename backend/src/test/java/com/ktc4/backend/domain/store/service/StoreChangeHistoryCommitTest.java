package com.ktc4.backend.domain.store.service;

import com.ktc4.backend.domain.job.entity.Job;
import com.ktc4.backend.domain.job.enums.JobStatus;
import com.ktc4.backend.domain.job.repository.JobRepository;
import com.ktc4.backend.domain.job.service.JobQueryService;
import com.ktc4.backend.domain.job.service.JobService;
import com.ktc4.backend.domain.store.dto.StoreChange;
import com.ktc4.backend.domain.store.dto.StoreSnapshot;
import com.ktc4.backend.domain.store.dto.StoreUpdateRequest;
import com.ktc4.backend.domain.store.entity.Store;
import com.ktc4.backend.domain.store.enums.StoreStatus;
import com.ktc4.backend.domain.store.repository.StoreRepository;
import com.ktc4.backend.domain.task.entity.Task;
import com.ktc4.backend.domain.task.enums.TaskClassification;
import com.ktc4.backend.domain.task.repository.TaskRepository;
import com.ktc4.backend.domain.task.service.TaskService;
import com.ktc4.backend.domain.verification.service.VerificationService;
import com.ktc4.backend.support.PostgresContainerTest;
import com.ktc4.backend.support.StoreChangeHistoryRow;
import jakarta.persistence.EntityManager;
import org.hibernate.Hibernate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;

/**
 * 관리자 가게 수정·조사 수정안 반영의 변경 기록을 실제 커밋으로 본다 — 가게를 잠그는지, 바깥 트랜잭션이 되돌려지면 기록도
 * 사라지는지, 조사 반영이 가게를 잠그기 전에 읽지 않는지.
 *
 * <p>테스트 트랜잭션을 끄고({@code NOT_SUPPORTED}) 서비스가 스스로 연 트랜잭션을 본다. 롤백이 없으므로 만든 행만 직접
 * 지운다. 가게 정보는 모두 가짜 값이다.
 */
// 조사 쪽 DB 테스트와 같은 빈 묶음. StoreService 를 감시(spy)하므로 컨텍스트는 따로 뜬다
@Import({JobQueryService.class, JobService.class, TaskService.class, StoreService.class, StoreChangeHistoryService.class,
        InvestigationTargetSelector.class, VerificationService.class})
@ImportAutoConfiguration(ValidationAutoConfiguration.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("가게 변경 기록 커밋 (관리자 수정·조사 반영)")
class StoreChangeHistoryCommitTest extends PostgresContainerTest {

    private static final Logger log = LoggerFactory.getLogger(StoreChangeHistoryCommitTest.class);
    private static final long ADMIN_ID = 7L;

    @MockitoSpyBean
    private StoreService storeService;

    @Autowired
    private VerificationService verificationService;

    @Autowired
    private StoreChangeHistoryService storeChangeHistoryService;

    @Autowired
    private StoreRepository storeRepository;

    @Autowired
    private JobRepository jobRepository;

    @Autowired
    private TaskRepository taskRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private Store store;
    private Long jobId;
    private Long taskId;

    @BeforeEach
    void setUp() {
        store = storeRepository.save(Store.builder()
                .name("예시분식").nameNormalized("예시분식")
                .addressRoad("가상특별시 예시구 샘플로 123").addressNormalized("가상특별시예시구샘플로123")
                .status(StoreStatus.OPEN).phone("053-000-0001")
                .build());
        Job job = jobRepository.save(Job.builder()
                .requestedBy(String.valueOf(ADMIN_ID)).status(JobStatus.DONE).targetCount(1).completedCount(1).build());
        jobId = job.getJobId();
        taskId = taskRepository.save(Task.builder()
                .job(job).store(store).classification(TaskClassification.PRIORITY_CHECK)
                .proposedChanges(Map.of("status", "SUSPENDED"))
                .build()).getTaskId();
    }

    // 이 테스트가 만든 행만 외래 키 순서대로 지운다. 한 줄이 실패해도 나머지를 지운다
    @AfterEach
    void cleanUp() {
        deleteQuietly("delete from verification where task_id = ?", taskId);
        deleteQuietly("delete from store_change_history where store_id = ?", store.getStoreId());
        deleteQuietly("delete from task where task_id = ?", taskId);
        deleteQuietly("delete from job where job_id = ?", jobId);
        deleteQuietly("delete from store where store_id = ?", store.getStoreId());
    }

    private void deleteQuietly(String sql, Long id) {
        try {
            jdbcTemplate.update(sql, id);
        } catch (RuntimeException e) {
            log.warn("테스트 데이터 정리 실패 - sql={}, id={}", sql, id, e);
        }
    }

    private String storeName() {
        return jdbcTemplate.queryForObject("select name from store where store_id = ?", String.class, store.getStoreId());
    }

    @Test
    @DisplayName("관리자 수정은 가게 행을 잠근다 — 그래야 \"바꾸기 전\" 값이 그 사이 다른 저장이 커밋한 값과 어긋나지 않는다")
    void adminEditLocksStore() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            // 지금과 같은 값을 보낸다 — 값을 바꾸면 그 UPDATE 가 행을 잠가, 잠금 조회를 빼도 이 테스트가 통과해 버린다
            storeService.updateStore(store.getStoreId(), StoreUpdateRequest.ofBasic("예시분식", null, null, null),
                    StoreChange.storeEdit(ADMIN_ID));

            // 수정 트랜잭션이 열린 채로, 다른 연결에서 그 가게 행을 기다리지 않고 잠가 본다 — 잠겨 있어야 한다
            try (Connection other = dataSource.getConnection()) {
                other.setAutoCommit(false);
                try (PreparedStatement lock = other.prepareStatement(
                        "select store_id from store where store_id = ? for update nowait")) {
                    lock.setLong(1, store.getStoreId());
                    lock.executeQuery().close();
                    throw new AssertionError("관리자 수정이 가게 행을 잠그지 않았다");
                } finally {
                    other.rollback();
                }
            } catch (SQLException e) {
                // 55P03 = lock_not_available — nowait 이 잠금을 못 얻었다는 뜻. 그 밖의 오류는 그대로 드러낸다
                assertThat(e.getSQLState()).isEqualTo("55P03");
            }
        });
    }

    @Test
    @DisplayName("관리자 수정을 감싼 트랜잭션이 되돌려지면 가게도 기록도 남지 않는다 — 기록은 같은 트랜잭션에 들어간다")
    void adminEditRollbackLeavesNoHistory() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            storeService.updateStore(store.getStoreId(), StoreUpdateRequest.ofBasic("새이름", null, null, null),
                    StoreChange.storeEdit(ADMIN_ID));
            status.setRollbackOnly();
        });

        assertThat(storeName()).isEqualTo("예시분식");
        assertThat(StoreChangeHistoryRow.of(jdbcTemplate, store.getStoreId())).isEmpty();
    }

    @Test
    @DisplayName("조사 반영을 감싼 트랜잭션이 되돌려지면 기록도 남지 않는다")
    void taskApplyRollbackLeavesNoHistory() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            verificationService.apply(taskId, ADMIN_ID);
            status.setRollbackOnly();
        });

        assertThat(jdbcTemplate.queryForObject("select status from store where store_id = ?", String.class,
                store.getStoreId())).isEqualTo("OPEN");
        assertThat(jdbcTemplate.queryForObject("select count(*) from verification where task_id = ?", Long.class, taskId))
                .isZero();
        assertThat(StoreChangeHistoryRow.of(jdbcTemplate, store.getStoreId())).isEmpty();
    }

    @Test
    @DisplayName("기록은 가게를 바꾸는 트랜잭션 밖에서 부르면 거절한다 — 일어나지 않은 변경이 따로 커밋되지 않게")
    void recordingOutsideTransactionIsRejected() {
        Store found = storeRepository.findById(store.getStoreId()).orElseThrow();
        StoreSnapshot snapshot = new StoreSnapshot("\"a\"", "\"b\"", null, "\"OPEN\"", "[]", "[]", null);

        assertThatThrownBy(() -> storeChangeHistoryService.recordChanges(found, snapshot, snapshot,
                StoreChange.storeEdit(ADMIN_ID)))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    @DisplayName("조사 반영은 가게를 잠그는 자리에서 처음 읽는다 — 먼저 읽어 두면 잠금 조회가 낡은 가게를 돌려준다")
    void taskApplyReadsStoreFirstAtLock() {
        Long storeId = store.getStoreId();
        doAnswer(invocation -> {
            // 서비스의 트랜잭션 안 — 조사 결과는 이미 잠가 읽었고, 그 가게는 아직 지연 로딩 프록시여야 한다
            Task locked = entityManager.find(Task.class, taskId);
            assertThat(Hibernate.isInitialized(locked.getStore())).as("가게를 잠그기 전에 이미 읽었다").isFalse();
            return invocation.callRealMethod();
        }).when(storeService).findStoreForUpdate(storeId);

        verificationService.apply(taskId, ADMIN_ID);

        // 핵심은 위 answer 안의 "아직 프록시" 단언이다 — 잠금 조회가 불렸는지만 확인한다
        verify(storeService, atLeastOnce()).findStoreForUpdate(storeId);
        assertThat(StoreChangeHistoryRow.of(jdbcTemplate, storeId)).hasSize(1);
    }
}
