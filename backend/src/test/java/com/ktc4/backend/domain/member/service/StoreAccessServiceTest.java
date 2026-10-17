package com.ktc4.backend.domain.member.service;

import com.ktc4.backend.domain.member.entity.Member;
import com.ktc4.backend.domain.member.entity.OwnerInfo;
import com.ktc4.backend.domain.member.entity.StoreOwner;
import com.ktc4.backend.domain.member.enums.MemberRole;
import com.ktc4.backend.domain.store.entity.Store;
import com.ktc4.backend.domain.store.service.StoreChangeHistoryService;
import com.ktc4.backend.domain.store.service.StoreManagementService;
import com.ktc4.backend.domain.store.service.StoreService;
import com.ktc4.backend.domain.store.enums.StoreStatus;
import com.ktc4.backend.global.error.CustomException;
import com.ktc4.backend.global.error.ErrorCode;
import com.ktc4.backend.global.security.AuthMember;
import com.ktc4.backend.support.PostgresContainerTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * "그 가게를 다룰 수 있는 사람"의 규칙을 실제 DB 의 연결 기록으로 확인한다. 체크인·가게 상세·운영 정보 저장이 이 규칙을 같이 쓴다.
 * 계정·가게 정보는 모두 가짜 값이다.
 */
// 가게 관리 테스트들과 같은 구성으로 둬 스프링 컨텍스트를 함께 쓴다 — 구성마다 컨텍스트(와 DB 연결 풀)가 따로 떠서
// 테스트용 Postgres 의 연결 수 상한에 걸린다
@Import({StoreManagementService.class, StoreAccessService.class, StoreService.class, StoreChangeHistoryService.class})
@DisplayName("StoreAccessService")
class StoreAccessServiceTest extends PostgresContainerTest {

    private static final LocalDateTime LINKED_AT = LocalDateTime.of(2026, 10, 14, 10, 0);
    private static final long MISSING_STORE_ID = 999_999L;

    @Autowired
    private StoreAccessService storeAccessService;

    @Autowired
    private TestEntityManager entityManager;

    private Member admin;
    private Store store;
    private Store otherStore;

    @BeforeEach
    void setUp() {
        admin = entityManager.persistAndFlush(Member.admin("admin@example.com", "hash"));
        store = persistStore("예시분식");
        otherStore = persistStore("다른가게");
    }

    private Store persistStore(String name) {
        return entityManager.persistAndFlush(Store.builder()
                .name(name).nameNormalized(name)
                .addressRoad("가상특별시 예시구 샘플로 123").addressNormalized("가상특별시예시구샘플로123")
                .status(StoreStatus.OPEN)
                .build());
    }

    private Member persistOwner(String email) {
        Member owner = Member.ownerApplicant(email, "hash", OwnerInfo.builder()
                .bizNo("1234567890").storeName("예시분식").representativeName("홍길동").phone("01000000000")
                .build());
        owner.approve(LINKED_AT);
        return entityManager.persistAndFlush(owner);
    }

    private static AuthMember ownerOf(Member member) {
        return new AuthMember(member.getMemberId(), MemberRole.OWNER);
    }

    private void assertRejectedWith(Long storeId, AuthMember authMember, ErrorCode errorCode) {
        assertThatThrownBy(() -> storeAccessService.requireAccessTo(storeId, authMember))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(errorCode);
    }

    @Test
    @DisplayName("그 가게에 연결된 점주는 통과한다")
    void linkedOwnerPasses() {
        Member owner = persistOwner("owner@example.com");
        entityManager.persistAndFlush(StoreOwner.link(store, owner, admin, LINKED_AT));

        assertThatCode(() -> storeAccessService.requireAccessTo(store.getStoreId(), ownerOf(owner)))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("연결이 끊긴 점주는 403 — 끊긴 연결은 연결이 아니다")
    void unlinkedOwnerIsForbidden() {
        Member owner = persistOwner("owner@example.com");
        StoreOwner link = StoreOwner.link(store, owner, admin, LINKED_AT);
        link.unlink(admin, LINKED_AT.plusDays(1));
        entityManager.persistAndFlush(link);

        assertRejectedWith(store.getStoreId(), ownerOf(owner), ErrorCode.FORBIDDEN);
    }

    @Test
    @DisplayName("다른 가게에만 연결된 점주는 403")
    void ownerOfAnotherStoreIsForbidden() {
        Member owner = persistOwner("owner@example.com");
        entityManager.persistAndFlush(StoreOwner.link(otherStore, owner, admin, LINKED_AT));

        assertRejectedWith(store.getStoreId(), ownerOf(owner), ErrorCode.FORBIDDEN);
    }

    @Test
    @DisplayName("연결이 하나도 없는 점주는 403 — 없는 가게 번호여도 403 이다(가게가 있는지 알려 주지 않는다)")
    void ownerWithoutLinkIsForbiddenEvenForMissingStore() {
        Member owner = persistOwner("owner@example.com");

        assertRejectedWith(store.getStoreId(), ownerOf(owner), ErrorCode.FORBIDDEN);
        assertRejectedWith(MISSING_STORE_ID, ownerOf(owner), ErrorCode.FORBIDDEN);
    }

    @Test
    @DisplayName("관리자는 연결 없이 통과한다 — 없는 가게인지는 이 확인 뒤에 가게를 읽는 쪽이 404 로 알린다")
    void adminPasses() {
        AuthMember adminMember = new AuthMember(admin.getMemberId(), MemberRole.ADMIN);

        assertThatCode(() -> storeAccessService.requireAccessTo(store.getStoreId(), adminMember)).doesNotThrowAnyException();
        assertThatCode(() -> storeAccessService.requireAccessTo(MISSING_STORE_ID, adminMember)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("가입 상태 확인용 토큰(역할 없음)은 연결이 있어도 403")
    void signupStatusTokenIsForbidden() {
        Member owner = persistOwner("owner@example.com");
        entityManager.persistAndFlush(StoreOwner.link(store, owner, admin, LINKED_AT));

        assertRejectedWith(store.getStoreId(), AuthMember.signupStatus(owner.getMemberId()), ErrorCode.FORBIDDEN);
    }

    @Test
    @DisplayName("로그인 정보가 없으면 401 — 규칙이 잘못 열려도 통과시키지 않는다")
    void missingLoginIsUnauthorized() {
        assertRejectedWith(store.getStoreId(), null, ErrorCode.UNAUTHORIZED);
    }
}
