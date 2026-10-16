package com.ktc4.backend.domain.file.repository;

import com.ktc4.backend.domain.file.entity.PendingUpload;
import com.ktc4.backend.domain.file.enums.FileType;
import com.ktc4.backend.domain.file.enums.UploadKind;
import com.ktc4.backend.support.PostgresContainerTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 대기 업로드를 잠그는 쿼리를 실제 트랜잭션 두 개로 본다 — 가입 신청이 가져가는 것과 오래된 업로드 정리가 겹칠 때.
 *
 * <p>잠금은 서로 다른 트랜잭션 사이에서만 보이므로 테스트 트랜잭션을 끄고({@code NOT_SUPPORTED}) 직접 연다.
 * 롤백이 없으므로 만든 행만 직접 지운다.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("PendingUploadRepository 잠금")
class PendingUploadRepositoryLockTest extends PostgresContainerTest {

    private static final long WAIT_SECONDS = 10;

    @Autowired
    private PendingUploadRepository repository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private TransactionTemplate tx;
    private final List<String> createdKeys = new ArrayList<>();
    private final LocalDateTime cutoff = LocalDateTime.now().minusHours(3);

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(transactionManager);
    }

    @AfterEach
    void cleanUp() {
        createdKeys.forEach(key -> jdbcTemplate.update("delete from pending_upload where file_key = ?", key));
    }

    // 올린 지 다섯 시간 된(정리 대상인) 업로드
    private String saveExpired() {
        return save(UUID.randomUUID().toString());
    }

    private String save(String key) {
        repository.save(new PendingUpload(key, UploadKind.BIZ_CERT, FileType.PDF, "stored-" + key + ".pdf", 10));
        jdbcTemplate.update("update pending_upload set created_at = ? where file_key = ?",
                LocalDateTime.now().minusHours(5), key);
        createdKeys.add(key);
        return key;
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(WAIT_SECONDS * 2, TimeUnit.SECONDS)) {
                throw new IllegalStateException("기다리는 신호가 오지 않았다");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("가입 신청이 잡고 있는 행은 정리가 기다리지 않고 건너뛴다 — 가져가는 중인 파일을 지우지 않는다")
    void cleanupSkipsRowsLockedByClaim() throws Exception {
        String claiming = saveExpired();
        String idle = saveExpired();
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        CompletableFuture<Void> claim = CompletableFuture.runAsync(() -> tx.executeWithoutResult(status -> {
            repository.findAllForUpdateByFileKeyIn(List.of(claiming));
            locked.countDown();
            await(release);
        }));
        try {
            await(locked);
            List<String> seen = CompletableFuture.supplyAsync(() -> tx.execute(status ->
                            repository.findByCreatedAtBefore(cutoff).stream().map(PendingUpload::getFileKey).toList()))
                    .get(WAIT_SECONDS, TimeUnit.SECONDS);   // 기다리면 여기서 시간 초과로 실패한다

            assertThat(seen).contains(idle).doesNotContain(claiming);
        } finally {
            release.countDown();
            claim.get(WAIT_SECONDS, TimeUnit.SECONDS);
        }
    }

    @Test
    @DisplayName("정리가 먼저 잡은 행은 가입 신청이 기다렸다가, 정리가 지운 뒤라 찾지 못한다 — 지워진 파일이 신청에 붙지 않는다")
    void claimWaitsForCleanupAndFindsNothing() throws Exception {
        String expired = saveExpired();
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        CompletableFuture<Void> cleanup = CompletableFuture.runAsync(() -> tx.executeWithoutResult(status -> {
            List<PendingUpload> mine = repository.findByCreatedAtBefore(cutoff).stream()
                    .filter(upload -> upload.getFileKey().equals(expired))
                    .toList();
            repository.deleteAll(mine);
            repository.flush();
            locked.countDown();
            await(release);
        }));
        CompletableFuture<List<PendingUpload>> claim = null;
        try {
            await(locked);
            claim = CompletableFuture.supplyAsync(() ->
                    tx.execute(status -> repository.findAllForUpdateByFileKeyIn(List.of(expired))));
            Thread.sleep(500);
            assertThat(claim).isNotDone();   // 정리가 끝날 때까지 기다린다
        } finally {
            release.countDown();
            cleanup.get(WAIT_SECONDS, TimeUnit.SECONDS);
        }

        assertThat(claim.get(WAIT_SECONDS, TimeUnit.SECONDS)).isEmpty();
    }

    @Test
    @DisplayName("가입 신청은 파일 ID 순으로 잠근다 — 두 신청이 서로 다른 순서로 잠가 교착 상태가 되지 않게")
    void claimLocksInFileKeyOrder() {
        String c = save("cccccccc-0000-4000-8000-000000000000");
        String a = save("aaaaaaaa-0000-4000-8000-000000000000");
        String b = save("bbbbbbbb-0000-4000-8000-000000000000");

        List<String> keys = tx.execute(status -> repository.findAllForUpdateByFileKeyIn(List.of(c, a, b)).stream()
                .map(PendingUpload::getFileKey)
                .toList());

        assertThat(keys).containsExactly(a, b, c);
    }
}
