package com.ktc4.backend.domain.member.service;

import com.ktc4.backend.domain.business.enums.BusinessState;
import com.ktc4.backend.domain.member.dto.LinkedStoreDetailResponse;
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
import com.ktc4.backend.domain.store.dto.BusinessHourRequest;
import com.ktc4.backend.domain.store.dto.MenuSeed;
import com.ktc4.backend.domain.store.entity.Store;
import com.ktc4.backend.domain.store.service.StoreOperatingInfoService;
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
    private final StoreOperatingInfoService storeOperatingInfoService;

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
     * <p>신청서의 가게 전화·영업시간·메뉴를 가게의 빈 항목에만 옮겨 적는다({@link #fillStoreIfFirstStore}).
     * 가게를 잠그고 읽는다 — 서로 다른 점주를 같은 빈 가게에 동시에 승인해도 한 번만 옮겨 적게.
     *
     * @param memberId 승인할 회원 ID
     * @param storeId  연결할 가게 ID
     * @param adminId  승인하는 관리자의 회원 ID
     * @return 승인된 가입 신청
     * @throws CustomException 점주가 아니거나 없으면 {@code MEMBER_NOT_FOUND},
     *                         이미 승인·거절됐으면 {@code OWNER_ALREADY_REVIEWED},
     *                         가게가 없으면 {@code STORE_NOT_FOUND}
     */
    /**
     * 점주에게 지금 연결된 가게. 관리자가 끊을 대상을 고르는 데 쓴다.
     *
     * <p>연결을 끊는 API({@link #unlinkStore})는 있는데 <b>무엇이 연결돼 있는지 볼 방법이 없어</b> 화면에서
     * 끊을 대상을 고를 수 없었다(PROMPT-150). 끊긴 연결은 담지 않는다.
     *
     * @param memberId 점주 회원 ID
     * @return 연결된 가게. 없으면 빈 목록
     * @throws CustomException 점주가 아니거나 없으면 {@code MEMBER_NOT_FOUND}
     */
    public List<LinkedStoreDetailResponse> getLinkedStores(Long memberId) {
        memberRepository.findById(memberId)
                .filter(found -> found.getRole() == MemberRole.OWNER)
                .orElseThrow(() -> new CustomException(ErrorCode.MEMBER_NOT_FOUND));

        return storeOwnerRepository.findLinkedStoreDetailsByMemberId(memberId).stream()
                .map(LinkedStoreDetailResponse::from)
                .toList();
    }

    @Transactional
    public OwnerApplicationResponse approve(Long memberId, Long storeId, Long adminId) {
        // 잠그고 읽는다 — 같은 신청을 동시에 두 번 승인해 연결이 두 건 생기지 않게.
        Member member = findOwnerForUpdate(memberId);
        if (member.getStatus() != MemberStatus.PENDING) {
            throw new CustomException(ErrorCode.OWNER_ALREADY_REVIEWED);
        }
        Store store = storeService.findStoreForUpdate(storeId);
        fillStoreIfFirstStore(member, store);

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
     * <p>이 점주에게 살아 있는 다른 가게가 없을 때만 신청서의 가게 정보를 가게의 빈 항목에 옮겨 적는다 — 승인과 같은 규칙
     * ({@link #fillStoreIfFirstStore}). 그래서 잘못 고른 가게를 바로잡을 때는 <b>잘못된 가게를 먼저 끊고</b> 연결해야
     * 맞는 가게에 옮겨 적힌다. 반대 순서면 연결하는 순간 다른 가게가 살아 있어 옮겨 적지 않는다.
     * 잘못된 가게에 이미 옮겨 적힌 값은 연결을 끊어도 남는다 — 그 가게의 점주가 가게 관리에서 고친다.
     *
     * @param memberId 연결할 점주의 회원 ID
     * @param storeId  연결할 가게 ID
     * @param adminId  연결을 인정하는 관리자의 회원 ID
     * @throws CustomException 점주가 아니거나 없으면 {@code MEMBER_NOT_FOUND},
     *                         승인된 점주가 아니면 {@code OWNER_NOT_APPROVED},
     *                         가게가 없으면 {@code STORE_NOT_FOUND},
     *                         이미 연결돼 있으면 {@code STORE_ALREADY_LINKED}
     */
    /**
     * 거절한 가입 신청을 다시 승인 대기로 되돌린다.
     *
     * <p>거절된 계정은 로그인할 수 없고 같은 이메일로 다시 가입할 수도 없어서, <b>잘못 거절하면 되돌릴
     * 방법이 없었다</b>(PROMPT-150). 되돌리면 처리 기록(언제·누가 거절했는지)을 지우고 다시 대기로 둔다 —
     * 기록이 남아 있으면 화면이 처리된 건으로 읽는다.
     *
     * <p>되돌릴 수 있는 건 <b>거절된 신청뿐</b>이다. 승인된 점주는 가게 연결 끊기로 다룬다.
     *
     * @param memberId 되돌릴 점주 회원 ID
     * @param adminId  되돌리는 관리자 ID
     * @return 대기로 돌아간 신청
     * @throws CustomException 점주가 아니거나 없으면 {@code MEMBER_NOT_FOUND},
     *                         거절된 신청이 아니면 {@code OWNER_NOT_REJECTED}
     */
    @Transactional
    public OwnerApplicationResponse reopen(Long memberId, Long adminId) {
        Member member = findOwnerForUpdate(memberId);
        if (member.getStatus() != MemberStatus.REJECTED) {
            throw new CustomException(ErrorCode.OWNER_NOT_REJECTED);
        }
        member.reopen();
        log.info("점주 가입 거절 되돌림 - memberId={}, adminId={}", memberId, adminId);
        return OwnerApplicationResponse.from(member);
    }

    @Transactional
    public void linkStore(Long memberId, Long storeId, Long adminId) {
        // 잠그고 읽는다 — 같은 연결을 동시에 두 번 만들지 않게.
        Member member = findOwnerForUpdate(memberId);
        if (member.getStatus() != MemberStatus.APPROVED) {
            throw new CustomException(ErrorCode.OWNER_NOT_APPROVED);
        }
        Store store = storeService.findStoreForUpdate(storeId);
        Member admin = memberRepository.getReferenceById(adminId);
        LocalDateTime linkedAt = LocalDateTime.now();

        Optional<StoreOwner> existing = storeOwnerRepository.findByStoreIdAndMemberId(storeId, memberId);
        if (existing.isPresent() && existing.get().isActive()) {
            throw new CustomException(ErrorCode.STORE_ALREADY_LINKED);
        }
        // 연결을 만들거나 되살리기 전에 부른다 — 그 뒤면 방금 만든 연결이 "다른 가게"로 세어진다
        fillStoreIfFirstStore(member, store);
        if (existing.isPresent()) {
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
        // 연결 조회는 가게를 읽지 않는다(가게 ID 만 비교) — 그래서 아래 잠금 조회가 가게를 처음 읽는 것이 된다.
        StoreOwner link = storeOwnerRepository.findByStoreIdAndMemberId(storeId, memberId)
                .filter(StoreOwner::isActive)
                .orElseThrow(() -> new CustomException(ErrorCode.STORE_LINK_NOT_FOUND));
        // 가게를 잠그고 읽는다 — 가게는 바뀐 칸만이 아니라 행 전체를 다시 쓰므로, 낡은 가게로 쓰면 그 사이 다른
        // 승인이 채운 가게 전화 등을 지운다.
        Store store = storeService.findStoreForUpdate(storeId);

        link.unlink(memberRepository.getReferenceById(adminId), LocalDateTime.now());
        store.clearOwnerPhoneIfSame(member.getOwnerInfo().getPhone());
        log.info("가게 연결 끊음 - storeId={}, memberId={}, adminId={}", storeId, memberId, adminId);
    }

    // 이 점주에게 살아 있는 다른 가게가 없으면, 신청서의 가게 전화·영업시간·메뉴를 가게의 빈 항목에 옮겨 적는다.
    // 신청서는 가게 하나를 설명한 기록이라(주소도 하나다) 그 점주의 "그 가게" 하나에만 들어가야 한다. 승인할 때는
    // 연결이 아직 없으니 늘 옮겨 적고, 따로 연결할 때는 잘못 고른 가게를 끊은 뒤 맞는 가게에 잇는 경우만 옮겨 적는다.
    // 연결을 만들거나 되살리기 전에 불러야 한다 — 그 뒤면 방금 만든 연결이 "다른 가게"로 세어진다.
    // member 와 store 는 둘 다 잠근 것이어야 한다.
    private void fillStoreIfFirstStore(Member member, Store store) {
        boolean hasOtherStore = storeOwnerRepository.findLinkedStoresByMemberId(member.getMemberId()).stream()
                .anyMatch(linked -> !linked.getStoreId().equals(store.getStoreId()));
        if (hasOtherStore) {
            log.info("신청서 가게 정보를 옮기지 않음 - 점주에게 다른 가게가 연결돼 있음, memberId={}, storeId={}",
                    member.getMemberId(), store.getStoreId());
            return;
        }
        OwnerInfo application = member.getOwnerInfo();
        storeOperatingInfoService.fillIfEmpty(store, application.getStorePhone(),
                application.getBusinessHours().stream()
                        .map(hour -> new BusinessHourRequest(hour.day(), hour.open(), hour.close()))
                        .toList(),
                application.getMenus().stream().map(menu -> new MenuSeed(menu.name(), menu.photoFileId())).toList());
    }

    // 관리자 계정 ID 를 넣어도 "없음"으로 답한다 — 이 API 들로 관리자 계정의 존재를 알아낼 수 없게.
    private Member findOwnerForUpdate(Long memberId) {
        return memberRepository.findByIdForUpdate(memberId)
                .filter(found -> found.getRole() == MemberRole.OWNER)
                .orElseThrow(() -> new CustomException(ErrorCode.MEMBER_NOT_FOUND));
    }
}
