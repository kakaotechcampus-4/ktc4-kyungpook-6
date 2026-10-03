package com.ktc4.backend.domain.member.service;

import com.ktc4.backend.domain.member.dto.OwnerApplicationResponse;
import com.ktc4.backend.domain.member.entity.Member;
import com.ktc4.backend.domain.member.entity.OwnerInfo;
import com.ktc4.backend.domain.member.enums.MemberRole;
import com.ktc4.backend.domain.member.enums.MemberStatus;
import com.ktc4.backend.domain.member.repository.MemberRepository;
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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// 계정·사업자 정보는 모두 가짜 값이다.
@ExtendWith(MockitoExtension.class)
@DisplayName("OwnerApprovalService")
class OwnerApprovalServiceTest {

    @Mock
    private MemberRepository memberRepository;

    @InjectMocks
    private OwnerApprovalService ownerApprovalService;

    private static Member pendingOwner(long id) {
        Member owner = Member.ownerApplicant("owner@example.com", "hash",
                new OwnerInfo("1234567890", "예시분식", "홍길동"));
        ReflectionTestUtils.setField(owner, "memberId", id);
        return owner;
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
    @DisplayName("승인 대기 점주를 승인하면 승인 상태와 승인 시각이 남는다")
    void approvesPendingOwner() {
        Member owner = pendingOwner(3L);
        when(memberRepository.findById(3L)).thenReturn(Optional.of(owner));
        LocalDateTime before = LocalDateTime.now();

        OwnerApplicationResponse response = ownerApprovalService.approve(3L);

        assertThat(owner.getStatus()).isEqualTo(MemberStatus.APPROVED);
        assertThat(response.status()).isEqualTo(MemberStatus.APPROVED);
        assertThat(response.reviewedAt()).isAfterOrEqualTo(before);
    }

    @Test
    @DisplayName("이미 승인된 신청은 OWNER_ALREADY_REVIEWED")
    void rejectsAlreadyApproved() {
        Member owner = pendingOwner(3L);
        owner.approve(LocalDateTime.of(2026, 9, 29, 10, 0));
        when(memberRepository.findById(3L)).thenReturn(Optional.of(owner));

        assertThatThrownBy(() -> ownerApprovalService.approve(3L))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.OWNER_ALREADY_REVIEWED);
    }

    @Test
    @DisplayName("없는 회원이나 관리자 계정 ID 는 똑같이 MEMBER_NOT_FOUND — 관리자 계정 존재를 알려주지 않게")
    void hidesNonOwners() {
        Member admin = Member.admin("admin@example.com", "hash");
        when(memberRepository.findById(1L)).thenReturn(Optional.of(admin));
        when(memberRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> ownerApprovalService.approve(1L))
                .extracting("errorCode").isEqualTo(ErrorCode.MEMBER_NOT_FOUND);
        assertThatThrownBy(() -> ownerApprovalService.approve(99L))
                .extracting("errorCode").isEqualTo(ErrorCode.MEMBER_NOT_FOUND);
        assertThat(admin.getStatus()).isEqualTo(MemberStatus.APPROVED);
    }

    @Test
    @DisplayName("엔티티도 승인 대기 점주가 아니면 승인을 거부한다 — 다른 경로에서 잘못 불려도 상태가 안 바뀌게")
    void entityGuardsApprove() {
        Member admin = Member.admin("admin@example.com", "hash");

        assertThatThrownBy(() -> admin.approve(LocalDateTime.now()))
                .isInstanceOf(IllegalStateException.class);
    }
}
