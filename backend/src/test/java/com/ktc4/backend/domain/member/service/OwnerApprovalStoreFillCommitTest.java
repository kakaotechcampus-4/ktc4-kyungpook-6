package com.ktc4.backend.domain.member.service;

import com.ktc4.backend.domain.member.entity.Member;
import com.ktc4.backend.domain.member.entity.OwnerInfo;
import com.ktc4.backend.domain.member.entity.SignupBusinessHour;
import com.ktc4.backend.domain.member.entity.SignupMenu;
import com.ktc4.backend.domain.member.enums.MemberStatus;
import com.ktc4.backend.domain.member.repository.MemberRepository;
import com.ktc4.backend.domain.store.entity.Store;
import com.ktc4.backend.domain.store.enums.BusinessDay;
import com.ktc4.backend.domain.store.enums.StoreStatus;
import com.ktc4.backend.domain.store.repository.StoreRepository;
import com.ktc4.backend.domain.store.service.StoreOperatingInfoService;
import com.ktc4.backend.domain.store.service.StoreService;
import com.ktc4.backend.support.PostgresContainerTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 가게 빈 칸 채우기를 실제 커밋으로 본다 — 동시에 붙이기, 중간에 실패하면 되돌리기.
 *
 * <p>{@code @DataJpaTest} 의 테스트 트랜잭션은 서비스를 감싸 버려서, 두 요청이 서로의 커밋을 기다리는 상황도 되돌리기도
 * 만들 수 없다. 그래서 이 클래스만 테스트 트랜잭션을 끄고({@code NOT_SUPPORTED}) 서비스가 스스로 연 트랜잭션을 본다.
 * 롤백이 없으므로 만든 행만 직접 지운다. 계정·가게 정보는 모두 가짜 값이다.
 */
@Import({OwnerApprovalService.class, StoreService.class, StoreOperatingInfoService.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("점주 승인 가게 채우기 커밋")
class OwnerApprovalStoreFillCommitTest extends PostgresContainerTest {

    private static final Duration WAIT_LIMIT = Duration.ofSeconds(10);

    @Autowired
    private OwnerApprovalService ownerApprovalService;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private StoreRepository storeRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private final List<Long> memberIds = new ArrayList<>();
    private final List<Long> storeIds = new ArrayList<>();
    private Long adminId;
    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        adminId = saveMember(Member.admin(uniqueEmail(), "hash"));
        executor = Executors.newFixedThreadPool(2);
    }

    // 이 테스트가 만든 행만 외래 키 순서대로 지운다 — 다른 테스트 데이터까지 지우지 않게.
    @AfterEach
    void cleanUp() throws InterruptedException {
        // 실패한 테스트의 스레드가 아직 잠금을 쥐고 있으면 지우기가 그 뒤에 막힌다 — 먼저 끝나기를 기다린다
        executor.shutdownNow();
        executor.awaitTermination(WAIT_LIMIT.toSeconds(), TimeUnit.SECONDS);
        for (Long storeId : storeIds) {
            jdbcTemplate.update("delete from store_owner where store_id = ?", storeId);
            jdbcTemplate.update("delete from store_menu where store_id = ?", storeId);
            jdbcTemplate.update("delete from store_business_hour where store_id = ?", storeId);
        }
        for (Long memberId : memberIds) {
            jdbcTemplate.update("delete from store_owner where member_id = ? or linked_by = ? or unlinked_by = ?",
                    memberId, memberId, memberId);
        }
        memberIds.forEach(id -> jdbcTemplate.update("delete from member where member_id = ?", id));
        storeIds.forEach(id -> jdbcTemplate.update("delete from store where store_id = ?", id));
    }

    private static String uniqueEmail() {
        return "commit-" + UUID.randomUUID() + "@example.com";
    }

    private Long saveMember(Member member) {
        Long id = memberRepository.save(member).getMemberId();
        memberIds.add(id);
        return id;
    }

    private Long saveStore() {
        Long id = storeRepository.save(Store.builder()
                .name("예시분식").nameNormalized("예시분식")
                .addressRoad("가상특별시 예시구 샘플로 123").addressNormalized("가상특별시예시구샘플로123")
                .status(StoreStatus.OPEN)
                .build()).getStoreId();
        storeIds.add(id);
        return id;
    }

    private Long saveApplicant(String storePhone, List<String> menuNames) {
        return saveApplicant("01000000000", storePhone, menuNames);
    }

    private Long saveApplicant(String ownerPhone, String storePhone, List<String> menuNames) {
        return saveMember(Member.ownerApplicant(uniqueEmail(), "hash", OwnerInfo.builder()
                .bizNo("1234567890").storeName("예시분식").representativeName("홍길동").phone(ownerPhone)
                .storeAddress("가상특별시 예시구 샘플로 123").storePhone(storePhone)
                .businessHours(List.of(
                        new SignupBusinessHour(BusinessDay.MON, "11:00", "15:00"),
                        new SignupBusinessHour(BusinessDay.TUE, "11:00", "15:00")))
                .menus(menuNames.stream().map(SignupMenu::new).toList())
                .build()));
    }

    private int countRows(String table, Long storeId) {
        return jdbcTemplate.queryForObject("select count(*) from " + table + " where store_id = ?", Integer.class, storeId);
    }

    private String storePhone(Long storeId) {
        return jdbcTemplate.queryForObject("select phone from store where store_id = ?", String.class, storeId);
    }

    private int countActiveLinks(Long storeId) {
        return jdbcTemplate.queryForObject(
                "select count(*) from store_owner where store_id = ? and unlinked_at is null", Integer.class, storeId);
    }

    private String ownerPhone(Long storeId) {
        return jdbcTemplate.queryForObject("select owner_phone from store where store_id = ?", String.class, storeId);
    }

    private String memberStatus(Long memberId) {
        return jdbcTemplate.queryForObject("select status from member where member_id = ?", String.class, memberId);
    }

    // 이 DB 에서 다른 연결이 행 잠금을 기다리는지. 행 잠금 대기는 상대 트랜잭션 ID 를 기다리는 모양으로 보인다.
    private boolean someoneWaitsForRowLock() {
        Integer waiting = jdbcTemplate.queryForObject("""
                select count(*) from pg_locks l
                join pg_stat_activity a on a.pid = l.pid
                where not l.granted and l.locktype in ('transactionid', 'tuple')
                  and a.datname = current_database() and l.pid <> pg_backend_pid()
                """, Integer.class);
        return waiting != null && waiting > 0;
    }

    /**
     * 첫 요청이 가게를 채운 뒤 커밋하기 전에 두 번째 요청을 보낸다. 두 번째 요청이 끝나 버리거나(잠금이 없음)
     * 잠금을 기다리기 시작하면 첫 요청을 커밋시킨다. 그래서 잠금이 없을 때는 항상 둘 다 "비어 있다"고 본다.
     */
    private void runSecondWhileFirstIsOpen(Runnable first, Runnable second) throws Exception {
        CountDownLatch firstFilled = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        Future<?> firstResult = executor.submit(() -> transaction.executeWithoutResult(status -> {
            first.run();
            firstFilled.countDown();
            awaitQuietly(releaseFirst);
        }));
        assertThat(firstFilled.await(WAIT_LIMIT.toSeconds(), TimeUnit.SECONDS)).as("첫 요청이 채우기까지 끝냄").isTrue();

        Future<?> secondResult = executor.submit(second);
        Instant deadline = Instant.now().plus(WAIT_LIMIT);
        while (!secondResult.isDone() && !someoneWaitsForRowLock() && Instant.now().isBefore(deadline)) {
            Thread.sleep(10);
        }
        releaseFirst.countDown();

        firstResult.get(WAIT_LIMIT.toSeconds(), TimeUnit.SECONDS);
        secondResult.get(WAIT_LIMIT.toSeconds(), TimeUnit.SECONDS);
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            if (!latch.await(WAIT_LIMIT.toSeconds(), TimeUnit.SECONDS)) {
                throw new IllegalStateException("두 번째 요청을 기다리다 시간이 다 됐다");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("서로 다른 점주 둘을 같은 빈 가게에 동시에 승인해도 한 번만 옮겨 적고, 가게 전화는 먼저 승인된 점주의 것")
    void concurrentApprovalsFillOnce() throws Exception {
        Long storeId = saveStore();
        Long ownerA = saveApplicant("053-000-0001", List.of("가락국수", "나물비빔밥"));
        Long ownerB = saveApplicant("053-000-0002", List.of("다슬기국"));

        runSecondWhileFirstIsOpen(
                () -> ownerApprovalService.approve(ownerA, storeId, adminId),
                () -> ownerApprovalService.approve(ownerB, storeId, adminId));

        assertThat(countRows("store_business_hour", storeId)).isEqualTo(2);
        assertThat(countRows("store_menu", storeId)).isEqualTo(2);
        assertThat(storePhone(storeId)).isEqualTo("053-000-0001");
        assertThat(countRows("store_owner", storeId)).isEqualTo(2);
    }

    @Test
    @DisplayName("승인과, 다른 점주의 따로 연결이 같은 빈 가게에 동시에 와도 한 번만 옮겨 적는다")
    void concurrentApprovalAndLinkFillOnce() throws Exception {
        Long storeId = saveStore();
        Long wrongStoreId = saveStore();
        Long ownerA = saveApplicant("053-000-0001", List.of("가락국수", "나물비빔밥"));
        // B 는 승인됐지만 잘못 고른 가게를 끊어, 지금 살아 있는 가게가 없다 — 연결하면 채우는 대상
        Long ownerB = saveApplicant("053-000-0002", List.of("다슬기국"));
        ownerApprovalService.approve(ownerB, wrongStoreId, adminId);
        ownerApprovalService.unlinkStore(ownerB, wrongStoreId, adminId);

        runSecondWhileFirstIsOpen(
                () -> ownerApprovalService.approve(ownerA, storeId, adminId),
                () -> ownerApprovalService.linkStore(ownerB, storeId, adminId));

        assertThat(countRows("store_business_hour", storeId)).isEqualTo(2);
        assertThat(countRows("store_menu", storeId)).isEqualTo(2);
        assertThat(storePhone(storeId)).isEqualTo("053-000-0001");
        assertThat(countActiveLinks(storeId)).isEqualTo(2);
    }

    @Test
    @DisplayName("승인과, 같은 가게의 다른 점주 연결 끊기가 동시에 와도 끊기가 방금 채운 가게 전화를 지우지 않는다")
    void concurrentApprovalAndUnlinkKeepFilledPhone() throws Exception {
        Long storeId = saveStore();
        // B 는 가게 전화 없이 가입해 승인됐다 — 가게의 점주 휴대폰 번호만 B 의 것으로 채워지고 가게 전화는 비어 있다
        Long ownerB = saveApplicant("01000000002", null, List.of("다슬기국"));
        ownerApprovalService.approve(ownerB, storeId, adminId);
        Long ownerA = saveApplicant("01000000001", "053-000-0001", List.of("가락국수"));

        // 끊기는 B 의 번호를 지우느라 가게 행을 다시 쓴다. 낡은 가게로 쓰면 A 가 채운 가게 전화가 사라진다
        runSecondWhileFirstIsOpen(
                () -> ownerApprovalService.approve(ownerA, storeId, adminId),
                () -> ownerApprovalService.unlinkStore(ownerB, storeId, adminId));

        assertThat(storePhone(storeId)).isEqualTo("053-000-0001");
        assertThat(ownerPhone(storeId)).isNull();
        assertThat(countActiveLinks(storeId)).isEqualTo(1);
    }

    @Test
    @DisplayName("옮겨 적다가 DB 가 거절하면 승인 전체가 되돌아간다 — 승인 대기 그대로, 연결·영업시간 행 없음")
    void failureWhileFillingRollsBackApproval() {
        Long storeId = saveStore();
        // 메뉴 이름이 칸 길이(50)를 넘어 메뉴를 넣는 순간 DB 가 거절한다 — 영업시간은 이미 넣은 뒤다
        Long ownerId = saveApplicant("053-000-0001", List.of("가".repeat(51)));

        assertThatThrownBy(() -> ownerApprovalService.approve(ownerId, storeId, adminId))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(memberStatus(ownerId)).isEqualTo(MemberStatus.PENDING.name());
        assertThat(countRows("store_owner", storeId)).isZero();
        assertThat(countRows("store_business_hour", storeId)).isZero();
        assertThat(countRows("store_menu", storeId)).isZero();
        assertThat(storePhone(storeId)).isNull();
    }

    @Test
    @DisplayName("신청서에 형식이 틀린 영업시간이 들어 있으면 건너뛰지 않고 승인이 실패한다 — 승인 대기 그대로, 아무것도 안 남음")
    void invalidStoredTimeFailsApproval() {
        Long storeId = saveStore();
        Long ownerId = saveApplicant("053-000-0001", List.of("가락국수"));
        jdbcTemplate.update("update member set store_business_hours = cast(? as jsonb) where member_id = ?",
                "[{\"day\":\"MON\",\"open\":\"25:00\",\"close\":\"21:00\"}]", ownerId);

        assertThatThrownBy(() -> ownerApprovalService.approve(ownerId, storeId, adminId))
                .isInstanceOf(IllegalStateException.class);

        assertThat(memberStatus(ownerId)).isEqualTo(MemberStatus.PENDING.name());
        assertThat(countRows("store_owner", storeId)).isZero();
        assertThat(countRows("store_business_hour", storeId)).isZero();
        assertThat(countRows("store_menu", storeId)).isZero();
        assertThat(storePhone(storeId)).isNull();
    }

    @Test
    @DisplayName("신청서 JSON 이 엉뚱한 모양이면 승인이 실패하고 승인 대기 그대로다 — 조용히 건너뛰지 않는다")
    void malformedApplicationJsonFailsApproval() {
        Long storeId = saveStore();
        Long ownerId = saveApplicant("053-000-0001", List.of("가락국수"));
        jdbcTemplate.update("update member set store_menus = cast(? as jsonb) where member_id = ?", "\"가락국수\"", ownerId);

        assertThatThrownBy(() -> ownerApprovalService.approve(ownerId, storeId, adminId))
                .isInstanceOf(DataAccessException.class);

        assertThat(memberStatus(ownerId)).isEqualTo(MemberStatus.PENDING.name());
        assertThat(countRows("store_owner", storeId)).isZero();
        assertThat(countRows("store_business_hour", storeId)).isZero();
    }
}
