package com.ktc4.backend.domain.member.service;

import com.ktc4.backend.domain.member.dto.OwnerApplicationResponse;
import com.ktc4.backend.domain.member.entity.Member;
import com.ktc4.backend.domain.member.enums.MemberRole;
import com.ktc4.backend.domain.member.enums.MemberStatus;
import com.ktc4.backend.domain.member.repository.MemberRepository;
import com.ktc4.backend.global.dto.PageResponse;
import com.ktc4.backend.global.error.CustomException;
import com.ktc4.backend.global.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 관리자가 점주 가입 신청을 확인하고 승인한다.
 *
 * <p>거절, 같은 사업자번호로 이미 승인된 계정 표시, 사업자 진위확인은 가게 연결과 함께 다음 단계에서 다룬다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OwnerApprovalService {

    private final MemberRepository memberRepository;

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
     * 승인 대기 중인 점주를 승인한다. 승인되면 그 점주는 로그인할 수 있다.
     *
     * @param memberId 승인할 회원 ID
     * @return 승인된 가입 신청
     * @throws CustomException 점주가 아니거나 없으면 {@code MEMBER_NOT_FOUND},
     *                         이미 승인·거절됐으면 {@code OWNER_ALREADY_REVIEWED}
     */
    @Transactional
    public OwnerApplicationResponse approve(Long memberId) {
        // 관리자 계정 ID 를 넣어도 "없음"으로 답한다 — 이 API 로 관리자 계정의 존재를 알아낼 수 없게.
        Member member = memberRepository.findById(memberId)
                .filter(found -> found.getRole() == MemberRole.OWNER)
                .orElseThrow(() -> new CustomException(ErrorCode.MEMBER_NOT_FOUND));
        if (member.getStatus() != MemberStatus.PENDING) {
            throw new CustomException(ErrorCode.OWNER_ALREADY_REVIEWED);
        }
        member.approve(LocalDateTime.now());
        return OwnerApplicationResponse.from(member);
    }
}
