package com.ktc4.backend.domain.member.service;

import com.ktc4.backend.domain.business.enums.BusinessState;
import com.ktc4.backend.domain.member.dto.OwnerApplicationResponse;
import com.ktc4.backend.domain.member.dto.StoreCandidateResponse;
import com.ktc4.backend.domain.member.entity.Member;
import com.ktc4.backend.domain.member.entity.OwnerInfo;
import com.ktc4.backend.domain.member.entity.StoreOwner;
import com.ktc4.backend.domain.member.enums.MemberRole;
import com.ktc4.backend.domain.member.enums.MemberStatus;
import com.ktc4.backend.domain.member.enums.NameMatch;
import com.ktc4.backend.domain.member.repository.MemberRepository;
import com.ktc4.backend.domain.member.repository.StoreOwnerRepository;
import com.ktc4.backend.domain.member.repository.StoreOwnerRepository.OwnerCount;
import com.ktc4.backend.domain.store.entity.Store;
import com.ktc4.backend.domain.store.enums.StoreStatus;
import com.ktc4.backend.domain.store.service.StoreService;
import com.ktc4.backend.domain.store.util.StoreNormalizer;
import com.ktc4.backend.global.dto.PageResponse;
import com.ktc4.backend.global.error.CustomException;
import com.ktc4.backend.global.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// 계정·사업자·가게 정보는 모두 가짜 값이다.
@ExtendWith(MockitoExtension.class)
@DisplayName("OwnerApprovalService")
class OwnerApprovalServiceTest {

    private static final long ADMIN_ID = 1L;
    private static final long STORE_ID = 10L;

    @Mock
    private MemberRepository memberRepository;

    @Mock
    private StoreOwnerRepository storeOwnerRepository;

    @Mock
    private StoreService storeService;

    @InjectMocks
    private OwnerApprovalService ownerApprovalService;

    private static Member pendingOwner(long id) {
        Member owner = Member.ownerApplicant("owner@example.com", "hash",
                new OwnerInfo("1234567890", "예시분식", "홍길동", "01000000000"));
        ReflectionTestUtils.setField(owner, "memberId", id);
        return owner;
    }

    private static Member admin() {
        Member admin = Member.admin("admin@example.com", "hash");
        ReflectionTestUtils.setField(admin, "memberId", ADMIN_ID);
        return admin;
    }

    private static Store store(long storeId, String name, String bizNo) {
        return store(storeId, name, bizNo, null);
    }

    private static Store store(long storeId, String name, String bizNo, String ownerPhone) {
        return store(storeId, name, bizNo, ownerPhone, null);
    }

    // ownerPhone 은 가게에 등록된 점주 개인 번호, storePhone 은 가게 전화번호(매장 번호)다
    private static Store store(long storeId, String name, String bizNo, String ownerPhone, String storePhone) {
        Store store = Store.builder()
                .name(name)
                .nameNormalized(StoreNormalizer.normalizeName(name))
                .addressRoad("가상특별시 예시구 샘플로 123")
                .addressNormalized("가상특별시예시구샘플로123")
                .status(StoreStatus.OPEN)
                .bizNo(bizNo)
                .ownerPhone(ownerPhone)
                .phone(storePhone)
                .build();
        ReflectionTestUtils.setField(store, "storeId", storeId);
        return store;
    }

    private static OwnerCount ownerCount(long storeId, long count) {
        return new OwnerCount() {
            @Override
            public Long getStoreId() {
                return storeId;
            }

            @Override
            public long getOwnerCount() {
                return count;
            }
        };
    }

    @Test
    @DisplayName("목록은 점주만, 요청한 상태로, 회원 ID 순서로 조회한다")
    void listsApplications() {
        when(memberRepository.findByRoleAndStatus(eq(MemberRole.OWNER), eq(MemberStatus.PENDING), any()))
                .thenReturn(new PageImpl<>(List.of(pendingOwner(3L)), PageRequest.of(0, 20), 1));

        PageResponse<OwnerApplicationResponse> response = ownerApprovalService.getApplications(MemberStatus.PENDING, 0, 20);

        assertThat(response.content()).singleElement().satisfies(application -> {
            assertThat(application.memberId()).isEqualTo(3L);
            assertThat(application.bizNo()).isEqualTo("1234567890");
            assertThat(application.storeName()).isEqualTo("예시분식");
            assertThat(application.representativeName()).isEqualTo("홍길동");
            assertThat(application.status()).isEqualTo(MemberStatus.PENDING);
        });
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(memberRepository).findByRoleAndStatus(eq(MemberRole.OWNER), eq(MemberStatus.PENDING), pageable.capture());
        assertThat(pageable.getValue().getSort().getOrderFor("memberId")).isNotNull();
    }

    @Test
    @DisplayName("후보 가게는 신청서의 사업자번호·휴대폰·상호명으로 찾고, 어느 단서가 맞았는지·국세청 상태·이미 연결된 점주 수를 함께 준다")
    void findsStoreCandidates() {
        when(memberRepository.findById(3L)).thenReturn(Optional.of(pendingOwner(3L)));
        when(storeService.findOwnerCandidates("1234567890", "01000000000", "예시분식")).thenReturn(List.of(
                store(10L, "예시분식", "1234567890", null),
                store(11L, "전혀다른이름", null, "010-0000-0000"),
                store(12L, "예시분식 2호점", null, null)));
        when(storeOwnerRepository.countOwnersByStoreIds(List.of(10L, 11L, 12L)))
                .thenReturn(List.of(ownerCount(10L, 1)));
        when(storeService.findNtsStates(List.of(10L, 11L, 12L)))
                .thenReturn(Map.of(10L, BusinessState.CLOSED));

        List<StoreCandidateResponse> candidates = ownerApprovalService.getStoreCandidates(3L);

        assertThat(candidates).extracting(
                        StoreCandidateResponse::storeId, StoreCandidateResponse::bizNoMatched,
                        StoreCandidateResponse::phoneMatched, StoreCandidateResponse::nameMatch,
                        StoreCandidateResponse::linkedOwnerCount, StoreCandidateResponse::ntsState)
                .containsExactly(
                        tuple(10L, true, false, NameMatch.EXACT, 1L, BusinessState.CLOSED),
                        // 점주 휴대폰 번호의 하이픈은 빼고 비교한다. 국세청 확인 전인 가게는 상태가 비어 있다
                        tuple(11L, false, true, NameMatch.NONE, 0L, null),
                        tuple(12L, false, false, NameMatch.PARTIAL, 0L, null));   // 상호명으로만 찾은 후보
    }

    @Test
    @DisplayName("가게 전화번호(매장 번호)는 그대로 주고, 점주 개인 번호는 주지 않는다 — 일치 여부만 알려 준다")
    void exposesStorePhoneButNotOwnerPhone() {
        when(memberRepository.findById(3L)).thenReturn(Optional.of(pendingOwner(3L)));
        when(storeService.findOwnerCandidates(any(), any(), any())).thenReturn(List.of(
                store(10L, "예시분식", null, "010-0000-0002", "053-000-0000"),
                store(11L, "예시분식 2호점", null, null, null)));

        List<StoreCandidateResponse> candidates = ownerApprovalService.getStoreCandidates(3L);

        assertThat(candidates.get(0).phone()).isEqualTo("053-000-0000");
        assertThat(candidates.get(0).phoneMatched()).isFalse();
        // 응답 어디에도 남의 점주 개인 번호가 없다 — 끝자리 0002 는 이 번호에만 있다
        assertThat(candidates.get(0).toString()).doesNotContain("0002");
        assertThat(candidates.get(1).phone()).isNull();
    }

    @Test
    @DisplayName("가게 전화번호가 신청서의 번호와 같아도 휴대폰 일치로 보지 않는다 — 점주 개인 번호와만 비교한다")
    void storePhoneIsNotAClue() {
        when(memberRepository.findById(3L)).thenReturn(Optional.of(pendingOwner(3L)));
        when(storeService.findOwnerCandidates(any(), any(), any())).thenReturn(List.of(
                store(10L, "예시분식", null, null, "010-0000-0000")));

        assertThat(ownerApprovalService.getStoreCandidates(3L)).singleElement()
                .extracting(StoreCandidateResponse::phoneMatched).isEqualTo(false);
    }

    @Test
    @DisplayName("신청서의 사업자번호가 비어 있으면 번호가 빈 가게와도 일치로 보지 않는다")
    void emptyApplicantBizNoNeverMatches() {
        Member owner = Member.ownerApplicant("owner@example.com", "hash",
                new OwnerInfo("", "예시분식", "홍길동", "01000000000"));
        when(memberRepository.findById(3L)).thenReturn(Optional.of(owner));
        when(storeService.findOwnerCandidates(any(), any(), any())).thenReturn(List.of(
                store(10L, "예시분식", "", null),
                store(11L, "예시분식 2호점", null, null),
                store(12L, "예시분식 3호점", "-", null)));

        assertThat(ownerApprovalService.getStoreCandidates(3L))
                .extracting(StoreCandidateResponse::bizNoMatched)
                .containsExactly(false, false, false);
    }

    @Test
    @DisplayName("가게 번호의 표기가 달라도(하이픈) 같은 번호면 일치로 본다")
    void matchesRegardlessOfNotation() {
        when(memberRepository.findById(3L)).thenReturn(Optional.of(pendingOwner(3L)));
        when(storeService.findOwnerCandidates(any(), any(), any())).thenReturn(List.of(
                store(10L, "예시분식", "123-45-67890", "010 0000 0000")));

        assertThat(ownerApprovalService.getStoreCandidates(3L)).singleElement().satisfies(candidate -> {
            assertThat(candidate.bizNoMatched()).isTrue();
            assertThat(candidate.phoneMatched()).isTrue();
        });
    }

    @Test
    @DisplayName("휴대폰 번호 없이 가입한 점주는 휴대폰 일치로 표시되지 않는다 — 가게의 점주 휴대폰 번호가 비어 있어도")
    void applicantWithoutPhoneNeverMatchesByPhone() {
        Member owner = Member.ownerApplicant("owner@example.com", "hash",
                new OwnerInfo("1234567890", "예시분식", "홍길동", null));
        when(memberRepository.findById(3L)).thenReturn(Optional.of(owner));
        when(storeService.findOwnerCandidates("1234567890", null, "예시분식")).thenReturn(List.of(
                store(10L, "예시분식", "1234567890", null),
                store(11L, "예시분식 2호점", null, "")));

        assertThat(ownerApprovalService.getStoreCandidates(3L))
                .extracting(StoreCandidateResponse::phoneMatched)
                .containsExactly(false, false);
    }

    @Test
    @DisplayName("후보가 없으면 빈 목록이고, 연결 수를 세러 가지 않는다")
    void noCandidates() {
        when(memberRepository.findById(3L)).thenReturn(Optional.of(pendingOwner(3L)));
        when(storeService.findOwnerCandidates(any(), any(), any())).thenReturn(List.of());

        assertThat(ownerApprovalService.getStoreCandidates(3L)).isEmpty();
        verify(storeOwnerRepository, never()).countOwnersByStoreIds(any());
    }

    @Test
    @DisplayName("후보 조회도 없는 회원이나 관리자 계정 ID 는 똑같이 MEMBER_NOT_FOUND")
    void candidatesHideNonOwners() {
        when(memberRepository.findById(1L)).thenReturn(Optional.of(admin()));
        when(memberRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> ownerApprovalService.getStoreCandidates(1L))
                .extracting("errorCode").isEqualTo(ErrorCode.MEMBER_NOT_FOUND);
        assertThatThrownBy(() -> ownerApprovalService.getStoreCandidates(99L))
                .extracting("errorCode").isEqualTo(ErrorCode.MEMBER_NOT_FOUND);
    }

    @Test
    @DisplayName("승인하면 승인 상태가 되고, 고른 가게에 그 관리자 이름으로 같은 시각에 연결된다")
    void approvesAndLinksStore() {
        Member owner = pendingOwner(3L);
        Member admin = admin();
        Store store = store(STORE_ID, "예시분식", "1234567890");
        when(memberRepository.findByIdForUpdate(3L)).thenReturn(Optional.of(owner));
        when(memberRepository.getReferenceById(ADMIN_ID)).thenReturn(admin);
        when(storeService.findStore(STORE_ID)).thenReturn(store);
        LocalDateTime before = LocalDateTime.now();

        OwnerApplicationResponse response = ownerApprovalService.approve(3L, STORE_ID, ADMIN_ID);

        assertThat(owner.getStatus()).isEqualTo(MemberStatus.APPROVED);
        assertThat(response.status()).isEqualTo(MemberStatus.APPROVED);
        assertThat(response.reviewedAt()).isAfterOrEqualTo(before);

        ArgumentCaptor<StoreOwner> link = ArgumentCaptor.forClass(StoreOwner.class);
        verify(storeOwnerRepository).save(link.capture());
        assertThat(link.getValue().getStore()).isSameAs(store);
        assertThat(link.getValue().getMember()).isSameAs(owner);
        assertThat(link.getValue().getLinkedBy()).isSameAs(admin);
        assertThat(link.getValue().getLinkedAt()).isEqualTo(response.reviewedAt());
    }

    @Test
    @DisplayName("승인하면 가게에 비어 있던 점주 휴대폰 번호를 신청서의 번호로 채운다 — 가게 전화번호는 건드리지 않는다")
    void approveFillsMissingOwnerPhone() {
        Store store = store(STORE_ID, "예시분식", null, null, "053-000-0000");
        when(memberRepository.findByIdForUpdate(3L)).thenReturn(Optional.of(pendingOwner(3L)));
        when(memberRepository.getReferenceById(ADMIN_ID)).thenReturn(admin());
        when(storeService.findStore(STORE_ID)).thenReturn(store);

        ownerApprovalService.approve(3L, STORE_ID, ADMIN_ID);

        assertThat(store.getOwnerPhone()).isEqualTo("01000000000");
        assertThat(store.getPhone()).isEqualTo("053-000-0000");
    }

    @Test
    @DisplayName("가게에 점주 휴대폰 번호가 이미 있으면 승인해도 바꾸지 않는다 — 나중에 연결된 점주가 덮어쓰지 않게")
    void approveKeepsExistingOwnerPhone() {
        Store store = store(STORE_ID, "예시분식", null, "010-0000-0009");
        when(memberRepository.findByIdForUpdate(3L)).thenReturn(Optional.of(pendingOwner(3L)));
        when(memberRepository.getReferenceById(ADMIN_ID)).thenReturn(admin());
        when(storeService.findStore(STORE_ID)).thenReturn(store);

        ownerApprovalService.approve(3L, STORE_ID, ADMIN_ID);

        assertThat(store.getOwnerPhone()).isEqualTo("010-0000-0009");
    }

    @Test
    @DisplayName("휴대폰 번호 없이 가입한 점주를 승인해도 가게의 점주 휴대폰 번호는 비어 있는 그대로다")
    void approveWithoutApplicantPhoneLeavesOwnerPhoneEmpty() {
        Member owner = Member.ownerApplicant("owner@example.com", "hash",
                new OwnerInfo("1234567890", "예시분식", "홍길동", null));
        ReflectionTestUtils.setField(owner, "memberId", 3L);
        Store store = store(STORE_ID, "예시분식", null);
        when(memberRepository.findByIdForUpdate(3L)).thenReturn(Optional.of(owner));
        when(memberRepository.getReferenceById(ADMIN_ID)).thenReturn(admin());
        when(storeService.findStore(STORE_ID)).thenReturn(store);

        ownerApprovalService.approve(3L, STORE_ID, ADMIN_ID);

        assertThat(store.getOwnerPhone()).isNull();
    }

    @Test
    @DisplayName("가게의 사업자번호가 신청서와 달라도 승인할 수 있다 — 관리자가 이름으로 찾아 고른 경우")
    void approvesStoreWithDifferentBizNo() {
        Member owner = pendingOwner(3L);
        when(memberRepository.findByIdForUpdate(3L)).thenReturn(Optional.of(owner));
        when(memberRepository.getReferenceById(ADMIN_ID)).thenReturn(admin());
        when(storeService.findStore(STORE_ID)).thenReturn(store(STORE_ID, "예시분식", null));

        ownerApprovalService.approve(3L, STORE_ID, ADMIN_ID);

        assertThat(owner.getStatus()).isEqualTo(MemberStatus.APPROVED);
        verify(storeOwnerRepository).save(any());
    }

    @Test
    @DisplayName("가게가 없으면 STORE_NOT_FOUND 이고, 신청은 승인 대기 그대로 남는다 — 가게 없는 점주를 만들지 않는다")
    void keepsPendingWhenStoreMissing() {
        Member owner = pendingOwner(3L);
        when(memberRepository.findByIdForUpdate(3L)).thenReturn(Optional.of(owner));
        when(storeService.findStore(STORE_ID)).thenThrow(new CustomException(ErrorCode.STORE_NOT_FOUND));

        assertThatThrownBy(() -> ownerApprovalService.approve(3L, STORE_ID, ADMIN_ID))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.STORE_NOT_FOUND);

        assertThat(owner.getStatus()).isEqualTo(MemberStatus.PENDING);
        assertThat(owner.getOwnerInfo().getReviewedAt()).isNull();
        verify(storeOwnerRepository, never()).save(any());
    }

    @Test
    @DisplayName("이미 승인된 신청은 OWNER_ALREADY_REVIEWED 이고, 연결을 또 만들지 않는다")
    void rejectsAlreadyApproved() {
        Member owner = pendingOwner(3L);
        owner.approve(LocalDateTime.of(2026, 9, 29, 10, 0));
        when(memberRepository.findByIdForUpdate(3L)).thenReturn(Optional.of(owner));

        assertThatThrownBy(() -> ownerApprovalService.approve(3L, STORE_ID, ADMIN_ID))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.OWNER_ALREADY_REVIEWED);
        verify(storeOwnerRepository, never()).save(any());
    }

    @Test
    @DisplayName("없는 회원이나 관리자 계정 ID 는 똑같이 MEMBER_NOT_FOUND — 관리자 계정 존재를 알려주지 않게")
    void hidesNonOwners() {
        Member admin = admin();
        when(memberRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(admin));
        when(memberRepository.findByIdForUpdate(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> ownerApprovalService.approve(1L, STORE_ID, ADMIN_ID))
                .extracting("errorCode").isEqualTo(ErrorCode.MEMBER_NOT_FOUND);
        assertThatThrownBy(() -> ownerApprovalService.approve(99L, STORE_ID, ADMIN_ID))
                .extracting("errorCode").isEqualTo(ErrorCode.MEMBER_NOT_FOUND);
        assertThat(admin.getStatus()).isEqualTo(MemberStatus.APPROVED);
        verify(storeOwnerRepository, never()).save(any());
    }

    // ── 가입 거절 ─────────────────────────────────────────────

    @Test
    @DisplayName("거절하면 거절 상태가 되고, 누가 언제 거절했는지 남으며, 가게에 연결되지 않는다")
    void rejects() {
        Member owner = pendingOwner(3L);
        when(memberRepository.findByIdForUpdate(3L)).thenReturn(Optional.of(owner));
        LocalDateTime before = LocalDateTime.now();

        OwnerApplicationResponse response = ownerApprovalService.reject(3L, ADMIN_ID);

        assertThat(owner.getStatus()).isEqualTo(MemberStatus.REJECTED);
        assertThat(response.status()).isEqualTo(MemberStatus.REJECTED);
        assertThat(response.reviewedAt()).isAfterOrEqualTo(before);
        assertThat(owner.getOwnerInfo().getRejectedBy()).isEqualTo(ADMIN_ID);
        verify(storeOwnerRepository, never()).save(any());
    }

    @Test
    @DisplayName("이미 승인됐거나 거절된 신청은 다시 거절할 수 없다 — OWNER_ALREADY_REVIEWED 이고 상태가 바뀌지 않는다")
    void cannotRejectReviewed() {
        Member approved = pendingOwner(3L);
        approved.approve(LocalDateTime.of(2026, 9, 29, 10, 0));
        Member rejected = pendingOwner(4L);
        rejected.reject(LocalDateTime.of(2026, 9, 29, 10, 0), ADMIN_ID);
        when(memberRepository.findByIdForUpdate(3L)).thenReturn(Optional.of(approved));
        when(memberRepository.findByIdForUpdate(4L)).thenReturn(Optional.of(rejected));

        assertThatThrownBy(() -> ownerApprovalService.reject(3L, ADMIN_ID))
                .extracting("errorCode").isEqualTo(ErrorCode.OWNER_ALREADY_REVIEWED);
        assertThatThrownBy(() -> ownerApprovalService.reject(4L, ADMIN_ID))
                .extracting("errorCode").isEqualTo(ErrorCode.OWNER_ALREADY_REVIEWED);
        assertThat(approved.getStatus()).isEqualTo(MemberStatus.APPROVED);
        assertThat(approved.getOwnerInfo().getRejectedBy()).isNull();
    }

    @Test
    @DisplayName("거절된 신청은 승인할 수 없다 — OWNER_ALREADY_REVIEWED 이고 연결을 만들지 않는다")
    void cannotApproveRejected() {
        Member rejected = pendingOwner(3L);
        rejected.reject(LocalDateTime.of(2026, 9, 29, 10, 0), ADMIN_ID);
        when(memberRepository.findByIdForUpdate(3L)).thenReturn(Optional.of(rejected));

        assertThatThrownBy(() -> ownerApprovalService.approve(3L, STORE_ID, ADMIN_ID))
                .extracting("errorCode").isEqualTo(ErrorCode.OWNER_ALREADY_REVIEWED);
        assertThat(rejected.getStatus()).isEqualTo(MemberStatus.REJECTED);
        verify(storeOwnerRepository, never()).save(any());
    }

    @Test
    @DisplayName("거절도 없는 회원이나 관리자 계정 ID 는 똑같이 MEMBER_NOT_FOUND")
    void rejectHidesNonOwners() {
        Member admin = admin();
        when(memberRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(admin));
        when(memberRepository.findByIdForUpdate(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> ownerApprovalService.reject(1L, ADMIN_ID))
                .extracting("errorCode").isEqualTo(ErrorCode.MEMBER_NOT_FOUND);
        assertThatThrownBy(() -> ownerApprovalService.reject(99L, ADMIN_ID))
                .extracting("errorCode").isEqualTo(ErrorCode.MEMBER_NOT_FOUND);
        assertThat(admin.getStatus()).isEqualTo(MemberStatus.APPROVED);
    }

    @Test
    @DisplayName("엔티티도 승인 대기 점주가 아니면 거절을 거부한다")
    void entityGuardsReject() {
        assertThatThrownBy(() -> admin().reject(LocalDateTime.now(), ADMIN_ID))
                .isInstanceOf(IllegalStateException.class);
    }

    // ── 연결 끊기 · 다시 연결 ─────────────────────────────────────────────

    private static Member approvedOwner(long id) {
        Member owner = pendingOwner(id);
        owner.approve(LocalDateTime.of(2026, 9, 29, 10, 0));
        return owner;
    }

    private static StoreOwner activeLink(Store store, Member owner) {
        return StoreOwner.link(store, owner, admin(), LocalDateTime.of(2026, 9, 29, 10, 0));
    }

    @Test
    @DisplayName("연결을 끊으면 끊긴 연결이 되고, 누가 언제 끊었는지 남는다 — 계정은 승인 상태 그대로다")
    void unlinksStore() {
        Member owner = approvedOwner(3L);
        Member admin = admin();
        StoreOwner link = activeLink(store(STORE_ID, "예시분식", null), owner);
        when(memberRepository.findByIdForUpdate(3L)).thenReturn(Optional.of(owner));
        when(memberRepository.getReferenceById(ADMIN_ID)).thenReturn(admin);
        when(storeOwnerRepository.findByStoreIdAndMemberId(STORE_ID, 3L)).thenReturn(Optional.of(link));
        LocalDateTime before = LocalDateTime.now();

        ownerApprovalService.unlinkStore(3L, STORE_ID, ADMIN_ID);

        assertThat(link.isActive()).isFalse();
        assertThat(link.getUnlinkedAt()).isAfterOrEqualTo(before);
        assertThat(link.getUnlinkedBy()).isSameAs(admin);
        assertThat(owner.getStatus()).isEqualTo(MemberStatus.APPROVED);
        verify(storeOwnerRepository, never()).delete(any());   // 행을 지우지 않는다
    }

    @Test
    @DisplayName("연결을 끊으면 가게에 적힌 그 점주의 휴대폰 번호도 지운다 — 표기가 달라도. 가게 전화번호는 그대로다")
    void unlinkClearsThisOwnersPhone() {
        Member owner = approvedOwner(3L);
        Store store = store(STORE_ID, "예시분식", null, "010-0000-0000", "053-000-0000");
        when(memberRepository.findByIdForUpdate(3L)).thenReturn(Optional.of(owner));
        when(memberRepository.getReferenceById(ADMIN_ID)).thenReturn(admin());
        when(storeOwnerRepository.findByStoreIdAndMemberId(STORE_ID, 3L))
                .thenReturn(Optional.of(activeLink(store, owner)));

        ownerApprovalService.unlinkStore(3L, STORE_ID, ADMIN_ID);

        assertThat(store.getOwnerPhone()).isNull();
        assertThat(store.getPhone()).isEqualTo("053-000-0000");
    }

    @Test
    @DisplayName("가게에 적힌 번호가 다른 사람의 것이면 연결을 끊어도 지우지 않는다")
    void unlinkKeepsOtherOwnersPhone() {
        Member owner = approvedOwner(3L);
        Store store = store(STORE_ID, "예시분식", null, "010-0000-0009");
        when(memberRepository.findByIdForUpdate(3L)).thenReturn(Optional.of(owner));
        when(memberRepository.getReferenceById(ADMIN_ID)).thenReturn(admin());
        when(storeOwnerRepository.findByStoreIdAndMemberId(STORE_ID, 3L))
                .thenReturn(Optional.of(activeLink(store, owner)));

        ownerApprovalService.unlinkStore(3L, STORE_ID, ADMIN_ID);

        assertThat(store.getOwnerPhone()).isEqualTo("010-0000-0009");
    }

    @Test
    @DisplayName("연결된 적이 없거나 이미 끊긴 가게는 STORE_LINK_NOT_FOUND — 끊긴 기록을 덮어쓰지 않는다")
    void cannotUnlinkTwice() {
        Member owner = approvedOwner(3L);
        Member firstAdmin = admin();
        StoreOwner unlinked = activeLink(store(STORE_ID, "예시분식", null), owner);
        LocalDateTime firstUnlinkedAt = LocalDateTime.of(2026, 10, 1, 9, 0);
        unlinked.unlink(firstAdmin, firstUnlinkedAt);
        when(memberRepository.findByIdForUpdate(3L)).thenReturn(Optional.of(owner));
        when(storeOwnerRepository.findByStoreIdAndMemberId(STORE_ID, 3L)).thenReturn(Optional.of(unlinked));
        when(storeOwnerRepository.findByStoreIdAndMemberId(11L, 3L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> ownerApprovalService.unlinkStore(3L, STORE_ID, ADMIN_ID))
                .extracting("errorCode").isEqualTo(ErrorCode.STORE_LINK_NOT_FOUND);
        assertThatThrownBy(() -> ownerApprovalService.unlinkStore(3L, 11L, ADMIN_ID))
                .extracting("errorCode").isEqualTo(ErrorCode.STORE_LINK_NOT_FOUND);
        assertThat(unlinked.getUnlinkedAt()).isEqualTo(firstUnlinkedAt);
        assertThat(unlinked.getUnlinkedBy()).isSameAs(firstAdmin);
    }

    @Test
    @DisplayName("승인된 점주를 새 가게에 연결하면 연결이 생기고, 가게에 비어 있던 점주 휴대폰 번호를 채운다")
    void linksApprovedOwner() {
        Member owner = approvedOwner(3L);
        Member admin = admin();
        Store store = store(STORE_ID, "예시분식", null);
        when(memberRepository.findByIdForUpdate(3L)).thenReturn(Optional.of(owner));
        when(memberRepository.getReferenceById(ADMIN_ID)).thenReturn(admin);
        when(storeService.findStore(STORE_ID)).thenReturn(store);
        when(storeOwnerRepository.findByStoreIdAndMemberId(STORE_ID, 3L)).thenReturn(Optional.empty());

        ownerApprovalService.linkStore(3L, STORE_ID, ADMIN_ID);

        ArgumentCaptor<StoreOwner> link = ArgumentCaptor.forClass(StoreOwner.class);
        verify(storeOwnerRepository).save(link.capture());
        assertThat(link.getValue().getStore()).isSameAs(store);
        assertThat(link.getValue().getMember()).isSameAs(owner);
        assertThat(link.getValue().getLinkedBy()).isSameAs(admin);
        assertThat(link.getValue().isActive()).isTrue();
        assertThat(store.getOwnerPhone()).isEqualTo("01000000000");
    }

    @Test
    @DisplayName("끊겼던 가게에 다시 연결하면 그 행을 되살린다 — 새 행을 만들지 않고, 인정한 관리자와 시각은 이번 것이다")
    void relinksUnlinkedStore() {
        Member owner = approvedOwner(3L);
        Member admin = admin();
        Store store = store(STORE_ID, "예시분식", null);
        StoreOwner unlinked = activeLink(store, owner);
        unlinked.unlink(admin(), LocalDateTime.of(2026, 10, 1, 9, 0));
        when(memberRepository.findByIdForUpdate(3L)).thenReturn(Optional.of(owner));
        when(memberRepository.getReferenceById(ADMIN_ID)).thenReturn(admin);
        when(storeService.findStore(STORE_ID)).thenReturn(store);
        when(storeOwnerRepository.findByStoreIdAndMemberId(STORE_ID, 3L)).thenReturn(Optional.of(unlinked));
        LocalDateTime before = LocalDateTime.now();

        ownerApprovalService.linkStore(3L, STORE_ID, ADMIN_ID);

        assertThat(unlinked.isActive()).isTrue();
        assertThat(unlinked.getUnlinkedBy()).isNull();
        assertThat(unlinked.getLinkedAt()).isAfterOrEqualTo(before);
        assertThat(unlinked.getLinkedBy()).isSameAs(admin);
        verify(storeOwnerRepository, never()).save(any());
    }

    @Test
    @DisplayName("이미 연결된 가게에 또 연결하면 STORE_ALREADY_LINKED 이고, 연결 기록이 바뀌지 않는다")
    void cannotLinkTwice() {
        Member owner = approvedOwner(3L);
        Store store = store(STORE_ID, "예시분식", null);
        StoreOwner active = activeLink(store, owner);
        LocalDateTime linkedAt = active.getLinkedAt();
        when(memberRepository.findByIdForUpdate(3L)).thenReturn(Optional.of(owner));
        when(storeService.findStore(STORE_ID)).thenReturn(store);
        when(storeOwnerRepository.findByStoreIdAndMemberId(STORE_ID, 3L)).thenReturn(Optional.of(active));

        assertThatThrownBy(() -> ownerApprovalService.linkStore(3L, STORE_ID, ADMIN_ID))
                .extracting("errorCode").isEqualTo(ErrorCode.STORE_ALREADY_LINKED);
        assertThat(active.getLinkedAt()).isEqualTo(linkedAt);
        verify(storeOwnerRepository, never()).save(any());
    }

    @Test
    @DisplayName("승인 대기·거절 상태인 점주는 연결할 수 없다 — OWNER_NOT_APPROVED. 승인 없이 체크인 권한을 줄 수 없게")
    void cannotLinkUnapprovedOwner() {
        Member pending = pendingOwner(3L);
        Member rejected = pendingOwner(4L);
        rejected.reject(LocalDateTime.of(2026, 9, 29, 10, 0), ADMIN_ID);
        when(memberRepository.findByIdForUpdate(3L)).thenReturn(Optional.of(pending));
        when(memberRepository.findByIdForUpdate(4L)).thenReturn(Optional.of(rejected));

        assertThatThrownBy(() -> ownerApprovalService.linkStore(3L, STORE_ID, ADMIN_ID))
                .extracting("errorCode").isEqualTo(ErrorCode.OWNER_NOT_APPROVED);
        assertThatThrownBy(() -> ownerApprovalService.linkStore(4L, STORE_ID, ADMIN_ID))
                .extracting("errorCode").isEqualTo(ErrorCode.OWNER_NOT_APPROVED);
        assertThat(pending.getStatus()).isEqualTo(MemberStatus.PENDING);
        verify(storeOwnerRepository, never()).save(any());
    }

    @Test
    @DisplayName("없는 가게에는 연결할 수 없다 — STORE_NOT_FOUND")
    void cannotLinkMissingStore() {
        when(memberRepository.findByIdForUpdate(3L)).thenReturn(Optional.of(approvedOwner(3L)));
        when(storeService.findStore(STORE_ID)).thenThrow(new CustomException(ErrorCode.STORE_NOT_FOUND));

        assertThatThrownBy(() -> ownerApprovalService.linkStore(3L, STORE_ID, ADMIN_ID))
                .extracting("errorCode").isEqualTo(ErrorCode.STORE_NOT_FOUND);
        verify(storeOwnerRepository, never()).save(any());
    }

    @Test
    @DisplayName("연결·끊기도 없는 회원이나 관리자 계정 ID 는 똑같이 MEMBER_NOT_FOUND")
    void linkAndUnlinkHideNonOwners() {
        when(memberRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(admin()));
        when(memberRepository.findByIdForUpdate(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> ownerApprovalService.linkStore(1L, STORE_ID, ADMIN_ID))
                .extracting("errorCode").isEqualTo(ErrorCode.MEMBER_NOT_FOUND);
        assertThatThrownBy(() -> ownerApprovalService.unlinkStore(99L, STORE_ID, ADMIN_ID))
                .extracting("errorCode").isEqualTo(ErrorCode.MEMBER_NOT_FOUND);
    }

    @Test
    @DisplayName("엔티티도 승인 대기 점주가 아니면 승인을 거부한다 — 다른 경로에서 잘못 불려도 상태가 안 바뀌게")
    void entityGuardsApprove() {
        Member admin = Member.admin("admin@example.com", "hash");

        assertThatThrownBy(() -> admin.approve(LocalDateTime.now()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("엔티티도 점주가 아닌 회원은 가게에 연결하지 않는다")
    void entityGuardsLink() {
        Member admin = admin();

        assertThatThrownBy(() -> StoreOwner.link(store(STORE_ID, "예시분식", null), admin, admin, LocalDateTime.now()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
