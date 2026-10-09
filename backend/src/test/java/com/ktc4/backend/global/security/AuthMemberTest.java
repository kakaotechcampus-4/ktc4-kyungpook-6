package com.ktc4.backend.global.security;

import com.ktc4.backend.domain.member.enums.MemberRole;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("AuthMember")
class AuthMemberTest {

    @Test
    @DisplayName("일반 토큰의 사용자는 역할 이름의 권한을 가진다")
    void accessAuthorities() {
        AuthMember owner = new AuthMember(1L, MemberRole.OWNER);

        assertThat(owner.scope()).isEqualTo(TokenScope.ACCESS);
        assertThat(owner.authorities()).extracting(GrantedAuthority::getAuthority).containsExactly("ROLE_OWNER");
    }

    @Test
    @DisplayName("가입 상태 확인용 토큰의 사용자는 역할이 없고 전용 권한 하나만 가진다 — 점주·관리자 권한이 없다")
    void signupStatusAuthorities() {
        AuthMember applicant = AuthMember.signupStatus(1L);

        assertThat(applicant.role()).isNull();
        assertThat(applicant.authorities()).extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_SIGNUP_STATUS");
    }

    @Test
    @DisplayName("가입 상태 확인용 권한 이름은 어떤 역할 이름과도 겹치지 않는다 — 겹치면 그 역할의 API 가 열린다")
    void signupStatusRoleDoesNotCollideWithRoles() {
        assertThat(MemberRole.values()).extracting(Enum::name).doesNotContain(AuthMember.SIGNUP_STATUS_ROLE);
    }
}
