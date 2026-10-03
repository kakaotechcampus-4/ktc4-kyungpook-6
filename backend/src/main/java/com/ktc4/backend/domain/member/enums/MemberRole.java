package com.ktc4.backend.domain.member.enums;

// 로그인하는 사람의 종류. 역할마다 부를 수 있는 API 가 다르다(SecurityConfig 참고).
public enum MemberRole {
    ADMIN, // 관리자 — 가입 없이 기동 시 한 계정만 만든다
    OWNER  // 점주 — 가입 신청 후 관리자가 승인해야 로그인할 수 있다
}
