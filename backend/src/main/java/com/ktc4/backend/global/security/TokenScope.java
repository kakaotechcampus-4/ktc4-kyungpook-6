package com.ktc4.backend.global.security;

// 토큰의 용도. 용도에 따라 부를 수 있는 API 가 다르다(SecurityConfig 참고).
public enum TokenScope {
    ACCESS,        // 로그인해서 받는 일반 토큰 — 역할(관리자·점주)대로 API 를 부른다
    SIGNUP_STATUS  // 가입 신청 때 받는 토큰 — 자기 가입 상태(GET /api/auth/me)만 읽을 수 있다
}
