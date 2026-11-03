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
 * <p>아동 토큰({@link TokenScope#CHILD})으로 들어온 사용자는 회원이 아니다 — {@code memberId}·{@code role} 이 null 이고
 * 아동 번호는 {@code childId} 에만 있다. 회원 번호와 아동 번호는 각자 1부터 올라가 값이 겹치므로, 같은 칸에 두면
 * {@code memberId()} 를 무심코 부른 코드가 아동 번호를 남의 회원 번호로 읽는다.
 *
 * @param memberId 회원 ID. 아동이면 null
 * @param role     역할. 가입 상태 확인용·아동 토큰이면 null
 * @param scope    토큰의 용도
 * @param childId  아동 번호. 아동 토큰일 때만 있다
 */
public record AuthMember(Long memberId, MemberRole role, TokenScope scope, Long childId) {

    /** {@code hasRole(...)} 규칙에 쓰는 가입 상태 확인용 권한 이름. 역할(MemberRole)과 겹치지 않아야 한다. */
    public static final String SIGNUP_STATUS_ROLE = "SIGNUP_STATUS";

    /** {@code hasRole(...)} 규칙에 쓰는 아동 권한 이름. 역할(MemberRole)·가입 상태 권한과 겹치지 않아야 한다. */
    public static final String CHILD_ROLE = "CHILD";

    /**
     * 용도와 칸이 맞는지 확인한다 — 아동이면 아동 번호만, 회원이면 회원 번호만 있어야 한다. 섞인 사용자(예: 역할이 있는 아동)가
     * 만들어지면 권한을 정하는 쪽이 어느 칸을 믿을지 흔들린다.
     *
     * @throws IllegalArgumentException 용도가 없거나, 용도와 칸이 맞지 않으면
     */
    public AuthMember {
        if (scope == null) {
            throw new IllegalArgumentException("토큰 용도가 없습니다");
        }
        boolean childShaped = childId != null && memberId == null && role == null;
        boolean memberShaped = childId == null && memberId != null
                && (scope == TokenScope.SIGNUP_STATUS ? role == null : role != null);
        if (scope == TokenScope.CHILD ? !childShaped : !memberShaped) {
            throw new IllegalArgumentException("토큰 용도와 사용자 칸이 맞지 않습니다: " + scope);
        }
    }

    /** 로그인해서 받은 일반 토큰의 사용자. */
    public AuthMember(Long memberId, MemberRole role) {
        this(memberId, role, TokenScope.ACCESS, null);
    }

    /** 가입 상태 확인용 토큰의 사용자 — 역할이 없다. */
    public static AuthMember signupStatus(Long memberId) {
        return new AuthMember(memberId, null, TokenScope.SIGNUP_STATUS, null);
    }

    /** 아동 토큰의 사용자 — 회원 번호·역할이 없고 아동 번호만 있다. */
    public static AuthMember child(Long childId) {
        return new AuthMember(null, null, TokenScope.CHILD, childId);
    }

    /**
     * Spring Security 의 {@code hasRole("ADMIN")} 규칙이 알아보는 권한 이름({@code ROLE_ADMIN})으로 바꾼다.
     * 가입 상태 확인용·아동 토큰은 역할 권한 없이 전용 권한 하나만 가진다 — 역할이 null 이라 역할보다 먼저 본다.
     */
    public List<GrantedAuthority> authorities() {
        if (scope == TokenScope.CHILD) {
            return List.of(new SimpleGrantedAuthority("ROLE_" + CHILD_ROLE));
        }
        if (scope == TokenScope.SIGNUP_STATUS) {
            return List.of(new SimpleGrantedAuthority("ROLE_" + SIGNUP_STATUS_ROLE));
        }
        return List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }
}
