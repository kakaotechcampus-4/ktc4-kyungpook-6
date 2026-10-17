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

import static com.ktc4.backend.support.UploadFileFixtures.uploadFile;
import static org.assertj.core.api.Assertions.assertThat;

// 누가 어느 가게 사진을 볼 수 있는지는 이 쿼리들이 정한다 — 실제 Postgres 로 확인한다. 계정·가게 정보는 모두 가짜 값이다.
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
                uploadFile(owner, kind, type, UUID.randomUUID() + "." + type.extension(), 100L));
    }

    private List<Long> idsOfStore(Store store) {
        return uploadFileRepository.findOfStore(store.getStoreId(), UploadKind.STORE_PHOTO_KINDS).stream()
                .map(UploadFile::getUploadFileId)
                .toList();
    }

    // 점주가 가게 사진 열기(GET /api/files/{fileId})로 이 파일을 열 수 있는지
    private boolean visibleTo(UploadFile file, Member viewer) {
        return uploadFileRepository.findVisibleToOwner(file.getFileKey(), viewer.getMemberId(), UploadKind.STORE_PHOTO_KINDS)
                .isPresent();
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
        assertThat(visibleTo(storeImage, owner)).isTrue();
        assertThat(visibleTo(menuImage, owner)).isTrue();
        // 사업자등록증은 올린 점주 본인에게도 이 길로는 열리지 않는다
        assertThat(visibleTo(bizCert, owner)).isFalse();
    }

    @Test
    void 승인_전_신청자의_사진은_어느_가게의_사진도_아니고_본인에게도_보이지_않는다() {
        Store store = persistStore("예시분식");
        Member applicant = persistApplicant("pending@example.com");
        UploadFile storeImage = persistFile(applicant, UploadKind.STORE_IMAGE);
        entityManager.clear();

        assertThat(idsOfStore(store)).isEmpty();
        assertThat(visibleTo(storeImage, applicant)).isFalse();
    }

    @Test
    void 거절된_신청자의_사진은_보이지_않는다() {
        Member applicant = persistApplicant("rejected@example.com");
        applicant.reject(AT, admin.getMemberId());
        entityManager.flush();
        UploadFile storeImage = persistFile(applicant, UploadKind.STORE_IMAGE);
        entityManager.clear();

        assertThat(visibleTo(storeImage, applicant)).isFalse();
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
        assertThat(visibleTo(storeImage, owner)).isFalse();

        StoreOwner unlinked = entityManager.find(StoreOwner.class, linked.getStoreOwnerId());
        unlinked.relink(admin, AT.plusDays(2));
        entityManager.flush();
        entityManager.clear();
        assertThat(idsOfStore(store)).containsExactly(storeImage.getUploadFileId());
        assertThat(visibleTo(storeImage, owner)).isTrue();
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
        // 파일 ID 를 알아도 다른 가게 점주의 사진은 열리지 않는다
        assertThat(visibleTo(theirImage, me)).isFalse();
        assertThat(visibleTo(myImage, them)).isFalse();
    }

    @Test
    void 같은_가게에_연결된_다른_점주는_그_가게_사진을_본다() {
        Store store = persistStore("예시분식");
        Member uploader = persistApplicant("owner@example.com");
        Member coOwner = persistApplicant("co-owner@example.com");
        UploadFile menuImage = persistFile(uploader, UploadKind.MENU_IMAGE);
        link(store, uploader);
        link(store, coOwner);
        entityManager.clear();

        assertThat(visibleTo(menuImage, coOwner)).isTrue();
    }

    @Test
    void 올린_점주나_보는_점주_어느_한쪽이라도_연결이_끊기면_보이지_않는다() {
        Store store = persistStore("예시분식");
        Member uploader = persistApplicant("owner@example.com");
        Member coOwner = persistApplicant("co-owner@example.com");
        UploadFile menuImage = persistFile(uploader, UploadKind.MENU_IMAGE);
        StoreOwner uploaderLink = link(store, uploader);
        StoreOwner viewerLink = link(store, coOwner);

        // 올린 점주가 끊기면 — 가게 메뉴에 사진 ID 가 남아 있어도 열리지 않는다
        uploaderLink.unlink(admin, AT.plusDays(1));
        entityManager.flush();
        entityManager.clear();
        assertThat(visibleTo(menuImage, coOwner)).isFalse();

        // 다시 이은 뒤 보는 점주가 끊기면
        entityManager.find(StoreOwner.class, uploaderLink.getStoreOwnerId()).relink(admin, AT.plusDays(2));
        entityManager.find(StoreOwner.class, viewerLink.getStoreOwnerId()).unlink(admin, AT.plusDays(2));
        entityManager.flush();
        entityManager.clear();
        assertThat(visibleTo(menuImage, coOwner)).isFalse();
        assertThat(visibleTo(menuImage, uploader)).isTrue();
    }

    @Test
    void 다른_가게에서_예전에_끊긴_연결로는_보이지_않는다() {
        // 보는 점주가 예전에 이 가게에 연결됐다가 끊겼고 지금은 다른 가게에 연결돼 있는 경우
        Store store = persistStore("예시분식");
        Store elsewhere = persistStore("샘플카페");
        Member uploader = persistApplicant("owner@example.com");
        Member former = persistApplicant("former@example.com");
        UploadFile storeImage = persistFile(uploader, UploadKind.STORE_IMAGE);
        link(store, uploader);
        StoreOwner formerLink = link(store, former);
        formerLink.unlink(admin, AT.plusDays(1));
        entityManager.persistAndFlush(StoreOwner.link(elsewhere, former, admin, AT.plusDays(1)));
        entityManager.clear();

        assertThat(visibleTo(storeImage, former)).isFalse();
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
    void 없는_파일_ID_는_빈_값이다() {
        Member owner = persistApplicant("owner@example.com");
        String unknown = UUID.randomUUID().toString();

        assertThat(uploadFileRepository.findVisibleToOwner(unknown, owner.getMemberId(), UploadKind.STORE_PHOTO_KINDS))
                .isEmpty();
        assertThat(uploadFileRepository.findByFileKey(unknown)).isEmpty();
    }

    @Test
    void 파일_ID_로_찾을_때_종류를_걸면_사업자등록증은_걸리지_않는다() {
        Member owner = persistApplicant("owner@example.com");
        UploadFile storeImage = persistFile(owner, UploadKind.STORE_IMAGE);
        UploadFile bizCert = persistFile(owner, UploadKind.BIZ_CERT);
        entityManager.clear();

        assertThat(uploadFileRepository.findByFileKeyAndKindIn(storeImage.getFileKey(), UploadKind.STORE_PHOTO_KINDS))
                .map(UploadFile::getUploadFileId).contains(storeImage.getUploadFileId());
        assertThat(uploadFileRepository.findByFileKeyAndKindIn(bizCert.getFileKey(), UploadKind.STORE_PHOTO_KINDS))
                .isEmpty();
        // 관리자용 조회는 종류를 걸지 않는다
        assertThat(uploadFileRepository.findByFileKey(bizCert.getFileKey()))
                .map(UploadFile::getUploadFileId).contains(bizCert.getUploadFileId());
    }
}
