package com.ktc4.backend.domain.store.service;

import com.ktc4.backend.domain.member.entity.Member;
import com.ktc4.backend.domain.member.entity.OwnerInfo;
import com.ktc4.backend.domain.member.entity.StoreOwner;
import com.ktc4.backend.domain.member.enums.MemberRole;
import com.ktc4.backend.domain.member.repository.MemberRepository;
import com.ktc4.backend.domain.member.repository.StoreOwnerRepository;
import com.ktc4.backend.domain.member.service.StoreAccessService;
import com.ktc4.backend.domain.store.dto.BusinessHourRequest;
import com.ktc4.backend.domain.store.dto.MenuRequest;
import com.ktc4.backend.domain.store.dto.OperatingInfoRequest;
import com.ktc4.backend.domain.store.dto.StoreDetailResponse;
import com.ktc4.backend.domain.store.entity.Store;
import com.ktc4.backend.domain.store.entity.StoreBusinessHour;
import com.ktc4.backend.domain.store.entity.StoreMenu;
import com.ktc4.backend.domain.store.enums.BusinessDay;
import com.ktc4.backend.domain.store.enums.StoreStatus;
import com.ktc4.backend.domain.store.repository.StoreBusinessHourRepository;
import com.ktc4.backend.domain.store.repository.StoreMenuRepository;
import com.ktc4.backend.domain.store.repository.StoreRepository;
import com.ktc4.backend.global.error.CustomException;
import com.ktc4.backend.global.error.ErrorCode;
import com.ktc4.backend.global.security.AuthMember;
import com.ktc4.backend.support.PostgresContainerTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/**
 * 운영 정보 저장을 실제 커밋으로 본다 — 무엇이 바뀌고 무엇이 그대로인지, 실패하면 전부 되돌아가는지, 동시에 오면 어떻게 되는지.
 *
 * <p>{@code @DataJpaTest} 의 테스트 트랜잭션은 서비스를 감싸 버려서, 서비스가 쓰기 트랜잭션을 빠뜨린 실수도, 되돌리기도,
 * 잠금 대기도 만들 수 없다. 그래서 이 클래스만 테스트 트랜잭션을 끄고({@code NOT_SUPPORTED}) 서비스가 스스로 연
 * 트랜잭션을 본다. 롤백이 없으므로 만든 행만 직접 지운다. 계정·가게 정보는 모두 가짜 값이다.
 */
@Import({StoreManagementService.class, StoreAccessService.class, StoreService.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("가게 운영 정보 저장 커밋")
class StoreManagementCommitTest extends PostgresContainerTest {

    private static final Logger log = LoggerFactory.getLogger(StoreManagementCommitTest.class);

    private static final Duration WAIT_LIMIT = Duration.ofSeconds(10);
    // 첫 작업이 열어 둔 채로 버티는 상한 — 두 번째 작업의 대기를 지켜보는 시간(WAIT_LIMIT)보다 길어야, 대기를 못 봤을 때
    // 첫 작업이 먼저 시간 초과로 무너져 엉뚱한 오류가 나지 않는다
    private static final Duration FIRST_HOLD_LIMIT = Duration.ofSeconds(20);
    private static final LocalDateTime OLD_DELETED_AT = LocalDateTime.of(2026, 1, 1, 9, 0);

    @Autowired
    private StoreManagementService storeManagementService;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private StoreRepository storeRepository;

    @Autowired
    private StoreOwnerRepository storeOwnerRepository;

    @Autowired
    private StoreMenuRepository menuRepository;

    @Autowired
    private StoreBusinessHourRepository businessHourRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private final List<Long> memberIds = new ArrayList<>();
    private final List<Long> storeIds = new ArrayList<>();
    private ExecutorService executor;

    private Member admin;
    private Store store;
    private Store otherStore;
    private AuthMember owner;
    private Long ownerLinkId;
    private StoreMenu keptMenu;
    private StoreMenu droppedMenu;
    private StoreMenu alreadyDeletedMenu;

    @BeforeEach
    void setUp() {
        executor = Executors.newFixedThreadPool(2);
        admin = saveMember(Member.admin(uniqueEmail(), "hash"));
        store = saveStore("예시분식", "053-000-0001");
        otherStore = saveStore("다른가게", "053-000-0002");

        Member ownerMember = Member.ownerApplicant(uniqueEmail(), "hash", OwnerInfo.builder()
                .bizNo("1234567890").storeName("예시분식").representativeName("홍길동").phone("01000000000")
                .build());
        ownerMember.approve(LocalDateTime.now());
        ownerMember = saveMember(ownerMember);
        ownerLinkId = storeOwnerRepository.save(StoreOwner.link(store, ownerMember, admin, LocalDateTime.now()))
                .getStoreOwnerId();
        owner = new AuthMember(ownerMember.getMemberId(), MemberRole.OWNER);

        businessHourRepository.saveAll(List.of(
                StoreBusinessHour.of(store, BusinessDay.MON, 660, 1260),
                StoreBusinessHour.of(store, BusinessDay.TUE, 660, 1260)));
        keptMenu = menuRepository.save(StoreMenu.of(store, "김치찌개", true));
        droppedMenu = menuRepository.save(StoreMenu.of(store, "된장찌개", true));
        StoreMenu deleted = StoreMenu.of(store, "옛 메뉴", true);
        deleted.markDeleted(OLD_DELETED_AT);
        alreadyDeletedMenu = menuRepository.save(deleted);

        businessHourRepository.save(StoreBusinessHour.of(otherStore, BusinessDay.SUN, 600, 840));
        menuRepository.save(StoreMenu.of(otherStore, "남의 메뉴", true));
    }

    // 이 테스트가 만든 행만 외래 키 순서대로 지운다 — 같은 컨테이너를 쓰는 다른 테스트 데이터까지 지우지 않게.
    @AfterEach
    void cleanUp() throws InterruptedException {
        // 실패한 테스트의 스레드가 아직 잠금을 쥐고 있으면 지우기가 그 뒤에 막힌다 — 먼저 끝나기를 기다린다
        executor.shutdownNow();
        executor.awaitTermination(FIRST_HOLD_LIMIT.toSeconds(), TimeUnit.SECONDS);
        // 한 줄이 실패해도 나머지를 끝까지 지운다 — 중간에 멈추면 남은 행이 다른 테스트의 정리를 외래 키로 막는다
        for (Long storeId : storeIds) {
            deleteQuietly("delete from store_menu where store_id = ?", storeId);
            deleteQuietly("delete from store_business_hour where store_id = ?", storeId);
            deleteQuietly("delete from store_owner where store_id = ?", storeId);
        }
        memberIds.forEach(id -> deleteQuietly("delete from member where member_id = ?", id));
        storeIds.forEach(id -> deleteQuietly("delete from store where store_id = ?", id));
    }

    private void deleteQuietly(String sql, Long id) {
        try {
            jdbcTemplate.update(sql, id);
        } catch (RuntimeException e) {
            log.warn("테스트 데이터 정리 실패 - sql={}, id={}", sql, id, e);
        }
    }

    private static String uniqueEmail() {
        return "store-mgmt-" + UUID.randomUUID() + "@example.com";
    }

    private Member saveMember(Member member) {
        Member saved = memberRepository.save(member);
        memberIds.add(saved.getMemberId());
        return saved;
    }

    private Store saveStore(String name, String phone) {
        Store saved = storeRepository.save(Store.builder()
                .name(name).nameNormalized(name)
                .addressRoad("가상특별시 예시구 샘플로 123").addressNormalized("가상특별시예시구샘플로123")
                .status(StoreStatus.OPEN).phone(phone).ownerPhone("01000000009")
                .build());
        storeIds.add(saved.getStoreId());
        return saved;
    }

    private static OperatingInfoRequest request(String phone, List<BusinessHourRequest> hours, List<MenuRequest> menus) {
        return new OperatingInfoRequest(phone, hours, menus);
    }

    private static BusinessHourRequest hour(BusinessDay day, String open, String close) {
        return new BusinessHourRequest(day, open, close);
    }

    private String phoneOf(Store target) {
        return jdbcTemplate.queryForObject("select phone from store where store_id = ?", String.class, target.getStoreId());
    }

    private List<Long> hourIdsOf(Store target) {
        return jdbcTemplate.queryForList("select business_hour_id from store_business_hour where store_id = ? order by 1",
                Long.class, target.getStoreId());
    }

    private Map<String, Object> menuRow(Long menuId) {
        return jdbcTemplate.queryForMap("select name, on_sale, deleted_at from store_menu where menu_id = ?", menuId);
    }

    private int countRows(String sql, Object... args) {
        return jdbcTemplate.queryForObject(sql, Integer.class, args);
    }

    // 저장 전 상태가 그대로인지 — 전화, 영업시간 행(같은 ID), 메뉴 이름·판매 여부·삭제 표시
    private void assertNothingChanged(List<Long> hourIdsBefore) {
        assertThat(phoneOf(store)).isEqualTo("053-000-0001");
        assertThat(hourIdsOf(store)).isEqualTo(hourIdsBefore);
        assertThat(menuRow(keptMenu.getMenuId())).containsEntry("name", "김치찌개").containsEntry("on_sale", true)
                .containsEntry("deleted_at", null);
        assertThat(menuRow(droppedMenu.getMenuId())).containsEntry("deleted_at", null);
        assertThat(countRows("select count(*) from store_menu where store_id = ?", store.getStoreId())).isEqualTo(3);
    }

    @Test
    @DisplayName("저장하면 전화·영업시간·메뉴가 바뀌고, 빠진 메뉴는 행을 남긴 채 지운 것으로 표시된다 — 다른 가게는 그대로")
    void savesOperatingInfo() {
        List<Long> oldHourIds = hourIdsOf(store);

        StoreDetailResponse saved = storeManagementService.save(store.getStoreId(), request("053-777-0000",
                List.of(hour(BusinessDay.SUN, "10:00", "14:00"), hour(BusinessDay.FRI, "21:00", "02:00")),
                List.of(new MenuRequest(keptMenu.getMenuId(), "김치찌개 특", false), new MenuRequest(null, "라면", true))),
                owner);

        // 가게: 전화만 바뀌고 이름·점주 휴대폰·상태는 그대로
        assertThat(jdbcTemplate.queryForMap("select phone, name, owner_phone, status from store where store_id = ?",
                store.getStoreId()))
                .containsEntry("phone", "053-777-0000").containsEntry("name", "예시분식")
                .containsEntry("owner_phone", "01000000009").containsEntry("status", "OPEN");
        // 영업시간: 통째로 바뀜 — 옛 행은 없고, 분 단위로 저장됐다(자정 넘김 21:00~02:00 → 1260, 120)
        assertThat(hourIdsOf(store)).hasSize(2).doesNotContainAnyElementsOf(oldHourIds);
        assertThat(jdbcTemplate.query("select day, open_minute, close_minute from store_business_hour where store_id = ? order by 1",
                (rs, i) -> tuple(rs.getString(1), rs.getInt(2), rs.getInt(3)), store.getStoreId()))
                .containsExactly(tuple("FRI", 1260, 120), tuple("SUN", 600, 840));
        // 메뉴: 고친 것·새것·삭제 표시·원래 지운 것
        assertThat(menuRow(keptMenu.getMenuId())).containsEntry("name", "김치찌개 특").containsEntry("on_sale", false)
                .containsEntry("deleted_at", null);
        assertThat(menuRow(droppedMenu.getMenuId()).get("deleted_at")).isNotNull();
        assertThat(((java.sql.Timestamp) menuRow(alreadyDeletedMenu.getMenuId()).get("deleted_at")).toLocalDateTime())
                .isEqualTo(OLD_DELETED_AT);
        assertThat(countRows("select count(*) from store_menu where store_id = ?", store.getStoreId())).isEqualTo(4);
        // 다른 가게는 그대로 — 영업시간을 지우는 문장이 이 가게로만 좁혀져 있는지
        assertThat(countRows("select count(*) from store_business_hour where store_id = ?", otherStore.getStoreId()))
                .isEqualTo(1);
        assertThat(countRows("select count(*) from store_menu where store_id = ? and deleted_at is null",
                otherStore.getStoreId())).isEqualTo(1);
        // 응답 = 커밋 뒤 다시 읽은 상세. 새 메뉴는 ID 를 받았다
        assertThat(saved).isEqualTo(storeManagementService.getDetail(store.getStoreId(), owner));
        assertThat(saved.menus()).extracting(m -> m.name(), m -> m.onSale())
                .containsExactly(tuple("김치찌개 특", false), tuple("라면", true));
        assertThat(saved.menus().get(1).menuId()).isNotNull();
        assertThat(saved.businessHours()).extracting(h -> h.day(), h -> h.open(), h -> h.close())
                .containsExactly(tuple(BusinessDay.FRI, "21:00", "02:00"), tuple(BusinessDay.SUN, "10:00", "14:00"));
    }

    @Test
    @DisplayName("관리자는 연결 없이도 저장한다 — 가게 전화·메뉴 이름의 앞뒤 공백은 지워서 저장된다")
    void adminSavesAnyStore() {
        AuthMember adminMember = new AuthMember(admin.getMemberId(), MemberRole.ADMIN);

        storeManagementService.save(otherStore.getStoreId(), request(" 053-555-0000 ",
                List.of(hour(BusinessDay.MON, "11:00", "21:00")), List.of(new MenuRequest(null, "  우동 ", true))),
                adminMember);

        assertThat(phoneOf(otherStore)).isEqualTo("053-555-0000");
        assertThat(jdbcTemplate.queryForList("select name from store_menu where store_id = ? and deleted_at is null",
                String.class, otherStore.getStoreId())).containsExactly("우동");
    }

    @Test
    @DisplayName("남의 가게 menuId 가 섞이면 400 이고 DB 는 그대로다 — 함께 보낸 내 메뉴 이름도, 남의 메뉴도")
    void validatesEveryMenuBeforeWriting() {
        List<Long> hourIdsBefore = hourIdsOf(store);
        Long foreignMenuId = jdbcTemplate.queryForObject(
                "select menu_id from store_menu where store_id = ?", Long.class, otherStore.getStoreId());

        assertThatThrownBy(() -> storeManagementService.save(store.getStoreId(), request("053-777-0000",
                List.of(hour(BusinessDay.SUN, "10:00", "14:00")),
                List.of(new MenuRequest(keptMenu.getMenuId(), "바꾼 이름", false), new MenuRequest(foreignMenuId, "가로채기", true))),
                owner))
                .isInstanceOf(CustomException.class).extracting("errorCode").isEqualTo(ErrorCode.INVALID_REQUEST);

        assertNothingChanged(hourIdsBefore);
        assertThat(menuRow(foreignMenuId)).containsEntry("name", "남의 메뉴");
    }

    @Test
    @DisplayName("쓰는 도중에 DB 가 거절하면 이미 쓴 전화·영업시간까지 전부 되돌아간다")
    void rollsBackWhenWriteFailsMidway() {
        List<Long> hourIdsBefore = hourIdsOf(store);
        // 새 메뉴 이름이 칸 길이(50)를 넘어 메뉴를 넣는 순간 DB 가 거절한다 — 전화·영업시간은 이미 쓴 뒤다.
        // (요청 검증이 막는 값이지만 서비스를 직접 불러 쓰기 도중의 실패를 만든다. 메뉴 이름 칸 길이(StoreMenu.MAX_NAME_LENGTH)가
        // 바뀌면 이 값도 바꿔야 한다)
        assertThatThrownBy(() -> storeManagementService.save(store.getStoreId(), request("053-777-0000",
                List.of(hour(BusinessDay.SUN, "10:00", "14:00")),
                List.of(new MenuRequest(keptMenu.getMenuId(), "바꾼 이름", false), new MenuRequest(null, "가".repeat(51), true))),
                owner))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertNothingChanged(hourIdsBefore);
    }

    @Test
    @DisplayName("연결이 끊긴 점주의 저장은 403 이고 DB 는 그대로다")
    void unlinkedOwnerWritesNothing() {
        List<Long> hourIdsBefore = hourIdsOf(store);
        jdbcTemplate.update("update store_owner set unlinked_at = now(), unlinked_by = ? where store_owner_id = ?",
                admin.getMemberId(), ownerLinkId);

        assertThatThrownBy(() -> storeManagementService.save(store.getStoreId(), request("053-777-0000",
                List.of(hour(BusinessDay.SUN, "10:00", "14:00")), List.of(new MenuRequest(null, "라면", true))), owner))
                .isInstanceOf(CustomException.class).extracting("errorCode").isEqualTo(ErrorCode.FORBIDDEN);

        assertNothingChanged(hourIdsBefore);
    }

    @Test
    @DisplayName("앞선 저장이 지운 메뉴의 ID 를 다음 저장이 보내면 400 — 화면이 오래된 경우")
    void staleMenuIdAfterAnotherSave() {
        storeManagementService.save(store.getStoreId(), request("053-777-0000",
                List.of(hour(BusinessDay.MON, "11:00", "21:00")), List.of(new MenuRequest(keptMenu.getMenuId(), "김치찌개", true))),
                owner);

        assertThatThrownBy(() -> storeManagementService.save(store.getStoreId(), request("053-777-0000",
                List.of(hour(BusinessDay.MON, "11:00", "21:00")), List.of(new MenuRequest(droppedMenu.getMenuId(), "된장찌개", true))),
                owner))
                .isInstanceOf(CustomException.class).extracting("errorCode").isEqualTo(ErrorCode.INVALID_REQUEST);
    }

    // ── 동시에 오는 경우 ───────────────────────────────────────────────

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

    // 두 번째 작업의 결과(예외 포함)와, 첫 작업이 열려 있는 동안 두 번째가 잠금을 기다리는 것을 실제로 봤는지
    private record SecondRun<T>(Future<T> result, boolean observedWait) {
    }

    /**
     * 첫 작업이 트랜잭션을 연 채로 있을 때 두 번째 작업을 보낸다. 두 번째가 끝나 버리거나(잠금이 없음) 잠금을
     * 기다리기 시작하면 첫 작업을 커밋시킨다.
     */
    private <T> SecondRun<T> runSecondWhileFirstIsOpen(Runnable first, java.util.concurrent.Callable<T> second)
            throws Exception {
        CountDownLatch firstDone = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        Future<?> firstResult = executor.submit(() -> transaction.executeWithoutResult(status -> {
            first.run();
            firstDone.countDown();
            awaitQuietly(releaseFirst);
        }));
        assertThat(firstDone.await(WAIT_LIMIT.toSeconds(), TimeUnit.SECONDS)).as("첫 작업이 끝까지 감").isTrue();

        Future<T> secondResult = executor.submit(second);
        Instant deadline = Instant.now().plus(WAIT_LIMIT);
        boolean observedWait = false;
        while (!secondResult.isDone() && !observedWait && Instant.now().isBefore(deadline)) {
            observedWait = someoneWaitsForRowLock();
            if (!observedWait) {
                Thread.sleep(10);
            }
        }
        releaseFirst.countDown();
        firstResult.get(FIRST_HOLD_LIMIT.toSeconds(), TimeUnit.SECONDS);
        try {
            secondResult.get(WAIT_LIMIT.toSeconds(), TimeUnit.SECONDS);
        } catch (ExecutionException ignored) {
            // 두 번째 작업의 예외는 부른 쪽이 Future 로 확인한다
        }
        return new SecondRun<>(secondResult, observedWait);
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            if (!latch.await(FIRST_HOLD_LIMIT.toSeconds(), TimeUnit.SECONDS)) {
                throw new IllegalStateException("두 번째 작업을 기다리다 시간이 다 됐다");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("같은 가게를 동시에 저장하면 차례로 처리되고 나중 저장이 이긴다 — 영업시간·메뉴가 섞이거나 두 번 생기지 않는다")
    void concurrentSavesApplyInOrder() throws Exception {
        SecondRun<StoreDetailResponse> second = runSecondWhileFirstIsOpen(
                () -> storeManagementService.save(store.getStoreId(), request("053-111-1111",
                        List.of(hour(BusinessDay.MON, "09:00", "12:00")),
                        List.of(new MenuRequest(keptMenu.getMenuId(), "첫 저장", true), new MenuRequest(null, "첫 새 메뉴", true))),
                        owner),
                () -> storeManagementService.save(store.getStoreId(), request("053-222-2222",
                        List.of(hour(BusinessDay.TUE, "09:00", "12:00"), hour(BusinessDay.WED, "09:00", "12:00")),
                        List.of(new MenuRequest(keptMenu.getMenuId(), "나중 저장", true), new MenuRequest(null, "나중 새 메뉴", true))),
                        owner));

        assertThat(second.observedWait()).as("나중 저장이 가게 잠금을 기다렸다").isTrue();
        assertThat(second.result().get()).isNotNull();
        assertThat(phoneOf(store)).isEqualTo("053-222-2222");
        assertThat(hourIdsOf(store)).hasSize(2);
        assertThat(jdbcTemplate.queryForList("select name from store_menu where store_id = ? and deleted_at is null order by menu_id",
                String.class, store.getStoreId())).containsExactly("나중 저장", "나중 새 메뉴");
        assertThat(countRows("select count(*) from store_menu where store_id = ? and name = '첫 새 메뉴' and deleted_at is not null",
                store.getStoreId())).isEqualTo(1);
    }

    @Test
    @DisplayName("확인한 뒤 잠그기 전에 연결이 끊기면, 잠근 뒤 다시 확인해 403 — 끊긴 점주가 한 번 저장하는 틈을 막는다")
    void rechecksLinkAfterLockingStore() throws Exception {
        List<Long> hourIdsBefore = hourIdsOf(store);

        SecondRun<StoreDetailResponse> save = runSecondWhileFirstIsOpen(
                // 연결 끊기처럼: 가게를 잠그고 연결을 끊은 채 커밋하지 않고 있다
                () -> {
                    jdbcTemplate.queryForList("select store_id from store where store_id = ? for update", store.getStoreId());
                    jdbcTemplate.update("update store_owner set unlinked_at = now(), unlinked_by = ? where store_owner_id = ?",
                            admin.getMemberId(), ownerLinkId);
                },
                () -> storeManagementService.save(store.getStoreId(), request("053-777-0000",
                        List.of(hour(BusinessDay.SUN, "10:00", "14:00")), List.of(new MenuRequest(null, "라면", true))), owner));

        assertThat(save.observedWait()).as("저장이 가게 잠금을 기다렸다").isTrue();
        assertThatThrownBy(() -> save.result().get()).hasCauseInstanceOf(CustomException.class)
                .cause().extracting("errorCode").isEqualTo(ErrorCode.FORBIDDEN);
        assertNothingChanged(hourIdsBefore);
    }

    @Test
    @DisplayName("저장은 회원 행을 잠그지 않는다 — 승인·연결 끊기(회원 → 가게 순서)와 서로 기다리며 멈추지 않게")
    void saveDoesNotLockMember() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            storeManagementService.save(store.getStoreId(), request("053-777-0000",
                    List.of(hour(BusinessDay.SUN, "10:00", "14:00")), List.of(new MenuRequest(null, "라면", true))), owner);

            // 저장 트랜잭션이 열린 채로, 다른 연결에서 그 점주 회원 행을 기다리지 않고 잠가 본다
            try (Connection other = dataSource.getConnection()) {
                other.setAutoCommit(false);
                try (PreparedStatement lock = other.prepareStatement(
                        "select member_id from member where member_id = ? for update nowait")) {
                    lock.setLong(1, owner.memberId());
                    lock.executeQuery().close();
                } finally {
                    other.rollback();
                }
            } catch (java.sql.SQLException e) {
                // 55P03 = lock_not_available — nowait 이 잠금을 못 얻었다는 뜻. 그 밖의 오류는 그대로 드러낸다
                if ("55P03".equals(e.getSQLState())) {
                    throw new AssertionError("저장이 회원 행을 잠갔다", e);
                }
                throw new IllegalStateException(e);
            }
        });
    }

    @Test
    @DisplayName("가게는 바뀐 칸만 다시 쓴다 — 읽어 둔 사이 다른 쪽이 채운 가게 전화를 이름만 고친 저장이 지우지 않는다")
    void dynamicUpdateKeepsColumnsChangedByOthers() {
        TransactionTemplate other = new TransactionTemplate(transactionManager);
        other.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            Store stale = storeRepository.findById(store.getStoreId()).orElseThrow();
            // 다른 연결(새 트랜잭션)이 가게 전화를 바꾸고 커밋한다 — 이 트랜잭션은 바뀌기 전 값을 들고 있다
            other.executeWithoutResult(s -> jdbcTemplate.update(
                    "update store set phone = ? where store_id = ?", "053-999-0000", store.getStoreId()));
            stale.updateBasicInfo("새 이름", "새이름", null, null, null, null);
        });

        assertThat(jdbcTemplate.queryForMap("select name, phone from store where store_id = ?", store.getStoreId()))
                .containsEntry("name", "새 이름").containsEntry("phone", "053-999-0000");
    }
}
