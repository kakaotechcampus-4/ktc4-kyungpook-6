package com.ktc4.backend.domain.member.enums;

// 계정이 로그인할 수 있는 상태인지. 점주는 가입 신청 → 관리자 승인을 거친다.
public enum MemberStatus {
    PENDING,  // 가입 신청 후 관리자 승인 대기
    APPROVED, // 승인됨 — 로그인 가능. 관리자는 처음부터 이 상태다
    REJECTED  // 관리자가 거절함 — 로그인 불가
}
