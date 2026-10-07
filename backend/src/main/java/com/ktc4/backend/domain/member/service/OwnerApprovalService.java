package com.ktc4.backend.domain.member.service;

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
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 관리자가 점주 가입 신청을 확인하고, 가게를 정해 승인한다.
 *
 * <p>후보 가게는 서버가 찾아 주고 확정은 관리자가 한다. 거절, 연결 끊기, 사업자 진위확인은 다음 단계에서 다룬다.
 */
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
     * 가입 신청에 연결할 후보 가게를 찾는다. 신청서의 사업자등록번호가 같은 가게, 휴대폰 번호가 가게 전화번호와
     * 같은 가게, 상호명이 겹치는 가게 순서다.
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
        Map<Long, Long> ownerCounts = storeOwnerRepository
                .countOwnersByStoreIds(stores.stream().map(Store::getStoreId).toList()).stream()
                .collect(Collectors.toMap(OwnerCount::getStoreId, OwnerCount::getOwnerCount));

        return stores.stream()
                .map(store -> StoreCandidateResponse.of(
                        store, applicant, ownerCounts.getOrDefault(store.getStoreId(), 0L)))
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
        // 관리자 계정 ID 를 넣어도 "없음"으로 답한다 — 이 API 로 관리자 계정의 존재를 알아낼 수 없게.
        // 잠그고 읽는다 — 같은 신청을 동시에 두 번 승인해 연결이 두 건 생기지 않게.
        Member member = memberRepository.findByIdForUpdate(memberId)
                .filter(found -> found.getRole() == MemberRole.OWNER)
                .orElseThrow(() -> new CustomException(ErrorCode.MEMBER_NOT_FOUND));
        if (member.getStatus() != MemberStatus.PENDING) {
            throw new CustomException(ErrorCode.OWNER_ALREADY_REVIEWED);
        }
        Store store = storeService.findStore(storeId);

        LocalDateTime approvedAt = LocalDateTime.now();
        member.approve(approvedAt);
        storeOwnerRepository.save(
                StoreOwner.link(store, member, memberRepository.getReferenceById(adminId), approvedAt));
        return OwnerApplicationResponse.from(member);
    }
}
