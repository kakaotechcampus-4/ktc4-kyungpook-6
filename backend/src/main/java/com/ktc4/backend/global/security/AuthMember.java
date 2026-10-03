package com.ktc4.backend.global.security;

import com.ktc4.backend.domain.member.enums.MemberRole;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;

/**
 * 토큰으로 확인한 로그인 사용자. 컨트롤러에서 {@code @AuthenticationPrincipal} 로 받는다.
 *
 * @param memberId 회원 ID
 * @param role     역할
 */
public record AuthMember(Long memberId, MemberRole role) {

    /** Spring Security 의 {@code hasRole("ADMIN")} 규칙이 알아보는 권한 이름({@code ROLE_ADMIN})으로 바꾼다. */
    public List<GrantedAuthority> authorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }
}
