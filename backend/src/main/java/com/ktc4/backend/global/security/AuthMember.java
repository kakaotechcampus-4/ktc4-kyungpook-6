package com.ktc4.backend.global.security;

import com.ktc4.backend.domain.member.enums.MemberRole;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;

/**
 * 토큰으로 확인한 로그인 사용자. 컨트롤러에서 {@code @AuthenticationPrincipal} 로 받는다.
 *
 * <p>가입 상태 확인용 토큰({@link TokenScope#SIGNUP_STATUS})으로 들어온 사용자는 역할이 없다({@code role} 이 null).
 * 아직 승인되지 않은 신청자라 점주가 아니기 때문이다. 그래서 {@code role() == OWNER} 로 검사하는 코드는
 * 이 사용자를 통과시키지 않는다.
 *
 * @param memberId 회원 ID
 * @param role     역할. 가입 상태 확인용 토큰이면 null
 * @param scope    토큰의 용도
 */
public record AuthMember(Long memberId, MemberRole role, TokenScope scope) {

    /** {@code hasRole(...)} 규칙에 쓰는 가입 상태 확인용 권한 이름. 역할(MemberRole)과 겹치지 않아야 한다. */
    public static final String SIGNUP_STATUS_ROLE = "SIGNUP_STATUS";

    /** 로그인해서 받은 일반 토큰의 사용자. */
    public AuthMember(Long memberId, MemberRole role) {
        this(memberId, role, TokenScope.ACCESS);
    }

    /** 가입 상태 확인용 토큰의 사용자 — 역할이 없다. */
    public static AuthMember signupStatus(Long memberId) {
        return new AuthMember(memberId, null, TokenScope.SIGNUP_STATUS);
    }

    /**
     * Spring Security 의 {@code hasRole("ADMIN")} 규칙이 알아보는 권한 이름({@code ROLE_ADMIN})으로 바꾼다.
     * 가입 상태 확인용 토큰은 역할 권한 없이 전용 권한 하나만 가진다.
     */
    public List<GrantedAuthority> authorities() {
        if (scope == TokenScope.SIGNUP_STATUS) {
            return List.of(new SimpleGrantedAuthority("ROLE_" + SIGNUP_STATUS_ROLE));
        }
        return List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }
}
