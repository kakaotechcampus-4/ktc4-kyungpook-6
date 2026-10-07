package com.ktc4.backend.domain.member.repository;

import com.ktc4.backend.domain.member.entity.Member;
import com.ktc4.backend.domain.member.entity.OwnerInfo;
import com.ktc4.backend.domain.member.entity.StoreOwner;
import com.ktc4.backend.domain.member.repository.StoreOwnerRepository.OwnerCount;
import com.ktc4.backend.domain.store.entity.Store;
import com.ktc4.backend.domain.store.enums.StoreStatus;
import com.ktc4.backend.support.PostgresContainerTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

// 계정·가게 정보는 모두 가짜 값이다.
class StoreOwnerRepositoryTest extends PostgresContainerTest {

    private static final LocalDateTime LINKED_AT = LocalDateTime.of(2026, 10, 6, 10, 0);

    @Autowired
    private StoreOwnerRepository storeOwnerRepository;

    @Autowired
    private TestEntityManager entityManager;

    private Member admin;

    @BeforeEach
    void setUp() {
        admin = entityManager.persistAndFlush(Member.admin("admin@example.com", "hash"));
    }

    private Store persistStore(String name) {
        return entityManager.persistAndFlush(Store.builder()
                .name(name)
                .nameNormalized(name)
                .addressRoad("가상특별시 예시구 샘플로 123")
                .addressNormalized("가상특별시예시구샘플로123")
                .status(StoreStatus.OPEN)
                .build());
    }

    private Member persistOwner(String email) {
        Member owner = Member.ownerApplicant(email, "hash", new OwnerInfo("0000000000", "예시분식", "홍길동", "01000000000"));
        owner.approve(LINKED_AT);
        return entityManager.persistAndFlush(owner);
    }

    private void link(Store store, Member owner) {
        entityManager.persistAndFlush(StoreOwner.link(store, owner, admin, LINKED_AT));
    }

    @Test
    void 저장한_연결을_조회하면_가게와_점주와_인정한_관리자와_시각이_남아_있다() {
        Store store = persistStore("예시분식");
        Member owner = persistOwner("owner@example.com");
        Long id = entityManager.persistAndFlush(StoreOwner.link(store, owner, admin, LINKED_AT)).getStoreOwnerId();
        entityManager.clear();

        StoreOwner found = storeOwnerRepository.findById(id).orElseThrow();

        assertThat(found.getStore().getStoreId()).isEqualTo(store.getStoreId());
        assertThat(found.getMember().getMemberId()).isEqualTo(owner.getMemberId());
        assertThat(found.getLinkedBy().getMemberId()).isEqualTo(admin.getMemberId());
        assertThat(found.getLinkedAt()).isEqualTo(LINKED_AT);
    }

    @Test
    void 연결된_가게와_점주의_짝일_때만_연결이_있다고_답한다() {
        Store linkedStore = persistStore("예시분식");
        Store otherStore = persistStore("샘플카페");
        Member linkedOwner = persistOwner("linked@example.com");
        Member otherOwner = persistOwner("other@example.com");
        link(linkedStore, linkedOwner);
        entityManager.clear();

        assertThat(storeOwnerRepository.existsByStoreIdAndMemberId(
                linkedStore.getStoreId(), linkedOwner.getMemberId())).isTrue();
        // 가게만 맞거나 점주만 맞으면 연결이 아니다
        assertThat(storeOwnerRepository.existsByStoreIdAndMemberId(
                linkedStore.getStoreId(), otherOwner.getMemberId())).isFalse();
        assertThat(storeOwnerRepository.existsByStoreIdAndMemberId(
                otherStore.getStoreId(), linkedOwner.getMemberId())).isFalse();
    }

    @Test
    void 한_가게에_점주_여럿을_한_점주에_가게_여럿을_연결할_수_있다() {
        Store storeA = persistStore("예시분식");
        Store storeB = persistStore("샘플카페");
        Member ownerA = persistOwner("a@example.com");
        Member ownerB = persistOwner("b@example.com");

        link(storeA, ownerA);
        link(storeA, ownerB);
        link(storeB, ownerA);

        assertThat(storeOwnerRepository.count()).isEqualTo(3);
    }

    @Test
    void 같은_점주를_같은_가게에_두_번_연결하면_제약_이름과_함께_실패한다() {
        Store store = persistStore("예시분식");
        Member owner = persistOwner("owner@example.com");
        link(store, owner);

        assertThatThrownBy(() -> storeOwnerRepository.saveAndFlush(StoreOwner.link(store, owner, admin, LINKED_AT)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .rootCause()
                .hasMessageContaining(StoreOwner.STORE_MEMBER_UNIQUE_CONSTRAINT);
    }

    @Test
    void 인정한_관리자가_없으면_저장에_실패한다() {
        Store store = persistStore("예시분식");
        Member owner = persistOwner("owner@example.com");

        assertThatThrownBy(() -> storeOwnerRepository.saveAndFlush(StoreOwner.link(store, owner, null, LINKED_AT)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 가게별_점주_수를_세고_연결이_없는_가게는_결과에_없다() {
        Store two = persistStore("예시분식");
        Store one = persistStore("샘플카페");
        Store none = persistStore("예시국밥");
        Store notAsked = persistStore("샘플제과");
        Member ownerA = persistOwner("a@example.com");
        Member ownerB = persistOwner("b@example.com");
        link(two, ownerA);
        link(two, ownerB);
        link(one, ownerA);
        link(notAsked, ownerB);
        entityManager.clear();

        List<OwnerCount> counts = storeOwnerRepository.countOwnersByStoreIds(
                List.of(two.getStoreId(), one.getStoreId(), none.getStoreId()));

        assertThat(counts).extracting(OwnerCount::getStoreId, OwnerCount::getOwnerCount)
                .containsExactlyInAnyOrder(tuple(two.getStoreId(), 2L), tuple(one.getStoreId(), 1L));
    }
}
