package com.ktc4.backend.domain.file.repository;

import com.ktc4.backend.domain.file.entity.UploadFile;
import com.ktc4.backend.domain.file.enums.FileType;
import com.ktc4.backend.domain.file.enums.UploadKind;
import com.ktc4.backend.domain.member.entity.Member;
import com.ktc4.backend.domain.member.entity.OwnerInfo;
import com.ktc4.backend.domain.member.entity.StoreOwner;
import com.ktc4.backend.domain.store.entity.Store;
import com.ktc4.backend.domain.store.enums.StoreStatus;
import com.ktc4.backend.support.PostgresContainerTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// 무엇이 공개 사진인지는 이 쿼리들이 정한다 — 실제 Postgres 로 확인한다. 계정·가게 정보는 모두 가짜 값이다.
class UploadFileRepositoryTest extends PostgresContainerTest {

    private static final LocalDateTime AT = LocalDateTime.of(2026, 10, 14, 10, 0);

    @Autowired
    private UploadFileRepository uploadFileRepository;

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

    // 가입 신청만 한 상태(승인 대기)
    private Member persistApplicant(String email) {
        return entityManager.persistAndFlush(
                Member.ownerApplicant(email, "hash", OwnerInfo.builder()
                        .bizNo("0000000000").storeName("예시분식").representativeName("홍길동").phone("01000000000")
                        .build()));
    }

    private StoreOwner link(Store store, Member owner) {
        owner.approve(AT);
        return entityManager.persistAndFlush(StoreOwner.link(store, owner, admin, AT));
    }

    private UploadFile persistFile(Member owner, UploadKind kind) {
        FileType type = kind == UploadKind.BIZ_CERT ? FileType.PDF : FileType.JPEG;
        return entityManager.persistAndFlush(
                new UploadFile(owner, kind, type, UUID.randomUUID() + "." + type.extension(), 100L));
    }

    private List<Long> idsOfStore(Store store) {
        return uploadFileRepository.findOfStore(store.getStoreId(), UploadKind.PUBLIC_KINDS).stream()
                .map(UploadFile::getUploadFileId)
                .toList();
    }

    private boolean isPublic(UploadFile file) {
        return uploadFileRepository.findLinkedById(file.getUploadFileId(), UploadKind.PUBLIC_KINDS).isPresent();
    }

    @Test
    void 회원이_올린_파일을_올린_순서대로_읽는다() {
        Member owner = persistApplicant("owner@example.com");
        Member other = persistApplicant("other@example.com");
        UploadFile storeImage = persistFile(owner, UploadKind.STORE_IMAGE);
        UploadFile bizCert = persistFile(owner, UploadKind.BIZ_CERT);
        persistFile(other, UploadKind.STORE_IMAGE);
        entityManager.clear();

        List<UploadFile> found = uploadFileRepository.findByMemberMemberIdOrderByUploadFileIdAsc(owner.getMemberId());

        assertThat(found).extracting(UploadFile::getUploadFileId)
                .containsExactly(storeImage.getUploadFileId(), bizCert.getUploadFileId());
    }

    @Test
    void 가게에_연결된_점주의_대표_사진과_메뉴_사진만_가게의_사진이다_사업자등록증은_아니다() {
        Store store = persistStore("예시분식");
        Member owner = persistApplicant("owner@example.com");
        UploadFile storeImage = persistFile(owner, UploadKind.STORE_IMAGE);
        UploadFile menuImage = persistFile(owner, UploadKind.MENU_IMAGE);
        UploadFile bizCert = persistFile(owner, UploadKind.BIZ_CERT);
        link(store, owner);
        entityManager.clear();

        assertThat(idsOfStore(store)).containsExactly(storeImage.getUploadFileId(), menuImage.getUploadFileId());
        assertThat(isPublic(storeImage)).isTrue();
        assertThat(isPublic(menuImage)).isTrue();
        assertThat(isPublic(bizCert)).isFalse();
    }

    @Test
    void 승인_전_신청자의_사진은_어느_가게의_사진도_아니고_공개되지_않는다() {
        Store store = persistStore("예시분식");
        Member applicant = persistApplicant("pending@example.com");
        UploadFile storeImage = persistFile(applicant, UploadKind.STORE_IMAGE);
        entityManager.clear();

        assertThat(idsOfStore(store)).isEmpty();
        assertThat(isPublic(storeImage)).isFalse();
    }

    @Test
    void 거절된_신청자의_사진은_공개되지_않는다() {
        Member applicant = persistApplicant("rejected@example.com");
        applicant.reject(AT, admin.getMemberId());
        entityManager.flush();
        UploadFile storeImage = persistFile(applicant, UploadKind.STORE_IMAGE);
        entityManager.clear();

        assertThat(isPublic(storeImage)).isFalse();
    }

    @Test
    void 연결을_끊으면_사진이_내려가고_다시_연결하면_다시_보인다() {
        Store store = persistStore("예시분식");
        Member owner = persistApplicant("owner@example.com");
        UploadFile storeImage = persistFile(owner, UploadKind.STORE_IMAGE);
        StoreOwner linked = link(store, owner);

        linked.unlink(admin, AT.plusDays(1));
        entityManager.flush();
        entityManager.clear();
        assertThat(idsOfStore(store)).isEmpty();
        assertThat(isPublic(storeImage)).isFalse();

        StoreOwner unlinked = entityManager.find(StoreOwner.class, linked.getStoreOwnerId());
        unlinked.relink(admin, AT.plusDays(2));
        entityManager.flush();
        entityManager.clear();
        assertThat(idsOfStore(store)).containsExactly(storeImage.getUploadFileId());
        assertThat(isPublic(storeImage)).isTrue();
    }

    @Test
    void 가게를_잘못_골라_승인했다가_바로잡으면_사진이_옮겨_간다() {
        Store wrong = persistStore("잘못고른가게");
        Store right = persistStore("예시분식");
        Member owner = persistApplicant("owner@example.com");
        UploadFile storeImage = persistFile(owner, UploadKind.STORE_IMAGE);
        StoreOwner mistaken = link(wrong, owner);

        mistaken.unlink(admin, AT.plusDays(1));
        entityManager.persistAndFlush(StoreOwner.link(right, owner, admin, AT.plusDays(1)));
        entityManager.clear();

        assertThat(idsOfStore(wrong)).isEmpty();
        assertThat(idsOfStore(right)).containsExactly(storeImage.getUploadFileId());
    }

    @Test
    void 다른_가게_점주의_사진은_섞이지_않는다() {
        Store mine = persistStore("예시분식");
        Store theirs = persistStore("샘플카페");
        Member me = persistApplicant("owner@example.com");
        Member them = persistApplicant("other@example.com");
        UploadFile myImage = persistFile(me, UploadKind.STORE_IMAGE);
        UploadFile theirImage = persistFile(them, UploadKind.STORE_IMAGE);
        link(mine, me);
        link(theirs, them);
        entityManager.clear();

        assertThat(idsOfStore(mine)).containsExactly(myImage.getUploadFileId());
        assertThat(idsOfStore(theirs)).containsExactly(theirImage.getUploadFileId());
    }

    @Test
    void 한_점주가_가게_두_곳에_연결돼_있으면_두_가게에_같은_사진이_나온다() {
        // 알고 둔 한계다 — 가입할 때 올린 사진이 어느 가게 것인지는 따로 적어 두지 않는다
        Store first = persistStore("예시분식");
        Store second = persistStore("예시분식2호점");
        Member owner = persistApplicant("owner@example.com");
        UploadFile storeImage = persistFile(owner, UploadKind.STORE_IMAGE);
        link(first, owner);
        entityManager.persistAndFlush(StoreOwner.link(second, owner, admin, AT));
        entityManager.clear();

        assertThat(idsOfStore(first)).containsExactly(storeImage.getUploadFileId());
        assertThat(idsOfStore(second)).containsExactly(storeImage.getUploadFileId());
    }

    @Test
    void 없는_파일_번호는_공개_조회에서_빈_값이다() {
        assertThat(uploadFileRepository.findLinkedById(999_999L, UploadKind.PUBLIC_KINDS)).isEmpty();
    }
}
