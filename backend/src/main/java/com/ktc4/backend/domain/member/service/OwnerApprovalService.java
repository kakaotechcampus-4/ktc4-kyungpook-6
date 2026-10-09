package com.ktc4.backend.domain.member.service;

import com.ktc4.backend.domain.business.enums.BusinessState;
import com.ktc4.backend.domain.member.dto.OwnerApplicationResponse;
import com.ktc4.backend.domain.member.dto.StoreCandidateResponse;
import com.ktc4.backend.domain.member.entity.Member;
import com.ktc4.backend.domain.member.entity.OwnerInfo;
import com.ktc4.backend.domain.member.entity.StoreOwner;
import com.ktc4.backend.domain.member.enums.MemberRole;
import com.ktc4.backend.domain.member.enums.MemberStatus;
import com.ktc4.backend.domain.member.repository.MemberRepository;
import com.ktc4.backend.domain.member.repository.StoreOwnerRepository;
import com.ktc4.backend.domain.member.repository.StoreOwnerRepository.OwnerCount;
import com.ktc4.backend.domain.store.entity.Store;
import com.ktc4.backend.domain.store.service.StoreService;
import com.ktc4.backend.global.dto.PageResponse;
import com.ktc4.backend.global.error.CustomException;
import com.ktc4.backend.global.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 관리자가 점주 가입 신청을 확인하고, 가게를 정해 승인하거나 거절한다. 승인한 뒤에는 가게 연결을 끊거나 다시 잇는다.
 *
 * <p>후보 가게는 서버가 찾아 주고 확정은 관리자가 한다. 사람의 판단이라 틀릴 수 있으므로 되돌리는 수단
 * (거절, 연결 끊기, 다시 연결)을 함께 둔다. 사업자 진위확인은 다음 단계에서 다룬다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OwnerApprovalService {

    private final MemberRepository memberRepository;
    private final StoreOwnerRepository storeOwnerRepository;
    private final StoreService storeService;

    /**
     * 점주 가입 신청을 상태별로 신청 순서(회원 ID 오름차순)대로 조회한다.
     *
     * @param status 조회할 상태
     * @param page   0부터 시작하는 페이지 번호
     * @param limit  한 페이지당 건수
     * @return 가입 신청 목록
     */
    public PageResponse<OwnerApplicationResponse> getApplications(MemberStatus status, int page, int limit) {
        return PageResponse.of(
                memberRepository.findByRoleAndStatus(MemberRole.OWNER, status,
                        PageRequest.of(page, limit, Sort.by(Sort.Direction.ASC, "memberId"))),
                OwnerApplicationResponse::from);
    }

    /**
     * 가입 신청에 연결할 후보 가게를 찾는다. 신청서의 사업자등록번호가 같은 가게, 휴대폰 번호가 가게에 등록된
     * 점주 휴대폰 번호와 같은 가게, 상호명이 겹치는 가게 순서다.
     *
     * <p>가게마다 국세청 상태도 함께 준다. 배치가 확인해 둔 값을 읽을 뿐 국세청을 새로 조회하지 않는다.
     *
     * <p>가게마다 이미 연결된 점주 수를 함께 준다. 이미 점주가 있는 가게에 또 연결하는 것은 막지 않지만
     * (재가입, 공동 대표, 가게를 넘겨받은 경우가 있다) 관리자가 모르고 승인하지는 않게 하기 위해서다.
     *
     * @param memberId 가입 신청한 회원 ID
     * @return 후보 가게. 없으면 빈 목록
     * @throws CustomException 점주가 아니거나 없으면 {@code MEMBER_NOT_FOUND}
     */
    public List<StoreCandidateResponse> getStoreCandidates(Long memberId) {
        OwnerInfo applicant = memberRepository.findById(memberId)
                .filter(found -> found.getRole() == MemberRole.OWNER)
                .orElseThrow(() -> new CustomException(ErrorCode.MEMBER_NOT_FOUND))
                .getOwnerInfo();

        List<Store> stores = storeService.findOwnerCandidates(
                applicant.getBizNo(), applicant.getPhone(), applicant.getStoreName());
        if (stores.isEmpty()) {
            return List.of();
        }
        List<Long> storeIds = stores.stream().map(Store::getStoreId).toList();
        Map<Long, Long> ownerCounts = storeOwnerRepository.countOwnersByStoreIds(storeIds).stream()
                .collect(Collectors.toMap(OwnerCount::getStoreId, OwnerCount::getOwnerCount));
        Map<Long, BusinessState> ntsStates = storeService.findNtsStates(storeIds);

        return stores.stream()
                .map(store -> StoreCandidateResponse.of(
                        store, applicant, ownerCounts.getOrDefault(store.getStoreId(), 0L),
                        ntsStates.get(store.getStoreId())))
                .toList();
    }

    /**
     * 승인 대기 중인 점주를 승인하고, 관리자가 고른 가게에 연결한다. 승인되면 그 점주는 로그인해 그 가게에 체크인할 수 있다.
     *
     * <p>승인과 연결을 한 트랜잭션에 둔다. 나누면 "승인됐는데 가게가 없는 점주"가 생긴다.
     * 회원·가게를 모두 확인한 뒤에 상태를 바꾸므로, 가게가 없으면 신청은 승인 대기 그대로 남는다.
     *
     * <p>가게의 사업자등록번호가 신청서와 달라도 막지 않는다. 우리 가게 데이터에 번호가 없거나 틀린 곳이 있어
     * 관리자가 이름으로 찾아 고르는 경우가 있다. 누가 인정했는지는 연결에 남는다.
     *
     * <p>가게에 점주 휴대폰 번호가 비어 있으면 신청서의 번호로 채운다. 이미 있으면 바꾸지 않는다.
     *
     * @param memberId 승인할 회원 ID
     * @param storeId  연결할 가게 ID
     * @param adminId  승인하는 관리자의 회원 ID
     * @return 승인된 가입 신청
     * @throws CustomException 점주가 아니거나 없으면 {@code MEMBER_NOT_FOUND},
     *                         이미 승인·거절됐으면 {@code OWNER_ALREADY_REVIEWED},
     *                         가게가 없으면 {@code STORE_NOT_FOUND}
     */
    @Transactional
    public OwnerApplicationResponse approve(Long memberId, Long storeId, Long adminId) {
        // 잠그고 읽는다 — 같은 신청을 동시에 두 번 승인해 연결이 두 건 생기지 않게.
        Member member = findOwnerForUpdate(memberId);
        if (member.getStatus() != MemberStatus.PENDING) {
            throw new CustomException(ErrorCode.OWNER_ALREADY_REVIEWED);
        }
        Store store = storeService.findStore(storeId);

        LocalDateTime approvedAt = LocalDateTime.now();
        member.approve(approvedAt);
        // 가게에 점주 휴대폰 번호가 아직 없으면 신청서의 번호로 채운다 — 같은 점주가 다시 가입하면 이 번호로 가게를 찾는다.
        store.recordOwnerPhoneIfAbsent(member.getOwnerInfo().getPhone());
        storeOwnerRepository.save(
                StoreOwner.link(store, member, memberRepository.getReferenceById(adminId), approvedAt));
        return OwnerApplicationResponse.from(member);
    }

    /**
     * 승인 대기 중인 점주의 가입을 거절한다. 거절된 계정은 로그인할 수 없고, 가게에 연결되지 않는다.
     *
     * <p>누가 언제 거절했는지 남긴다. 거절을 되돌리는 방법은 아직 없다 — 같은 이메일로는 다시 가입할 수 없다.
     *
     * @param memberId 거절할 회원 ID
     * @param adminId  거절하는 관리자의 회원 ID
     * @return 거절된 가입 신청
     * @throws CustomException 점주가 아니거나 없으면 {@code MEMBER_NOT_FOUND},
     *                         이미 승인·거절됐으면 {@code OWNER_ALREADY_REVIEWED}
     */
    @Transactional
    public OwnerApplicationResponse reject(Long memberId, Long adminId) {
        // 잠그고 읽는다 — 같은 신청을 한 관리자는 승인, 다른 관리자는 거절하는 일이 동시에 일어나지 않게.
        Member member = findOwnerForUpdate(memberId);
        if (member.getStatus() != MemberStatus.PENDING) {
            throw new CustomException(ErrorCode.OWNER_ALREADY_REVIEWED);
        }
        member.reject(LocalDateTime.now(), adminId);
        log.info("점주 가입 거절 - memberId={}, adminId={}", memberId, adminId);
        return OwnerApplicationResponse.from(member);
    }

    /**
     * 이미 승인된 점주를 가게에 연결한다. 가게를 잘못 골라 승인했거나 실수로 연결을 끊었을 때 바로잡는 데 쓴다.
     *
     * <p>끊긴 적이 있는 가게면 그 연결을 되살린다. 가게에 점주 휴대폰 번호가 비어 있으면 승인 때와 같이 채운다.
     *
     * @param memberId 연결할 점주의 회원 ID
     * @param storeId  연결할 가게 ID
     * @param adminId  연결을 인정하는 관리자의 회원 ID
     * @throws CustomException 점주가 아니거나 없으면 {@code MEMBER_NOT_FOUND},
     *                         승인된 점주가 아니면 {@code OWNER_NOT_APPROVED},
     *                         가게가 없으면 {@code STORE_NOT_FOUND},
     *                         이미 연결돼 있으면 {@code STORE_ALREADY_LINKED}
     */
    @Transactional
    public void linkStore(Long memberId, Long storeId, Long adminId) {
        // 잠그고 읽는다 — 같은 연결을 동시에 두 번 만들지 않게.
        Member member = findOwnerForUpdate(memberId);
        if (member.getStatus() != MemberStatus.APPROVED) {
            throw new CustomException(ErrorCode.OWNER_NOT_APPROVED);
        }
        Store store = storeService.findStore(storeId);
        Member admin = memberRepository.getReferenceById(adminId);
        LocalDateTime linkedAt = LocalDateTime.now();

        Optional<StoreOwner> existing = storeOwnerRepository.findByStoreIdAndMemberId(storeId, memberId);
        if (existing.isPresent()) {
            if (existing.get().isActive()) {
                throw new CustomException(ErrorCode.STORE_ALREADY_LINKED);
            }
            existing.get().relink(admin, linkedAt);
        } else {
            storeOwnerRepository.save(StoreOwner.link(store, member, admin, linkedAt));
        }
        store.recordOwnerPhoneIfAbsent(member.getOwnerInfo().getPhone());
        log.info("가게 연결 - storeId={}, memberId={}, adminId={}", storeId, memberId, adminId);
    }

    /**
     * 점주와 가게의 연결을 끊는다. 끊는 즉시 그 점주는 그 가게에 체크인할 수 없다 — 체크인할 때마다 연결을 확인한다.
     *
     * <p>계정은 승인 상태 그대로 둔다. 로그인은 되지만 연결된 가게가 없으면 체크인할 곳이 없다.
     * 행을 지우지 않고 누가 언제 끊었는지 남긴다. 가게의 점주 휴대폰 번호가 이 점주의 번호면 함께 지운다.
     *
     * @param memberId 점주의 회원 ID
     * @param storeId  가게 ID
     * @param adminId  끊는 관리자의 회원 ID
     * @throws CustomException 점주가 아니거나 없으면 {@code MEMBER_NOT_FOUND},
     *                         그 가게에 연결돼 있지 않으면(이미 끊긴 경우 포함) {@code STORE_LINK_NOT_FOUND}
     */
    @Transactional
    public void unlinkStore(Long memberId, Long storeId, Long adminId) {
        Member member = findOwnerForUpdate(memberId);
        StoreOwner link = storeOwnerRepository.findByStoreIdAndMemberId(storeId, memberId)
                .filter(StoreOwner::isActive)
                .orElseThrow(() -> new CustomException(ErrorCode.STORE_LINK_NOT_FOUND));

        link.unlink(memberRepository.getReferenceById(adminId), LocalDateTime.now());
        link.getStore().clearOwnerPhoneIfSame(member.getOwnerInfo().getPhone());
        log.info("가게 연결 끊음 - storeId={}, memberId={}, adminId={}", storeId, memberId, adminId);
    }

    // 관리자 계정 ID 를 넣어도 "없음"으로 답한다 — 이 API 들로 관리자 계정의 존재를 알아낼 수 없게.
    private Member findOwnerForUpdate(Long memberId) {
        return memberRepository.findByIdForUpdate(memberId)
                .filter(found -> found.getRole() == MemberRole.OWNER)
                .orElseThrow(() -> new CustomException(ErrorCode.MEMBER_NOT_FOUND));
    }
}
