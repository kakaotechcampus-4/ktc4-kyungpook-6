package com.ktc4.backend.global.security;

import com.ktc4.backend.domain.member.enums.MemberRole;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    @Test
    @DisplayName("아동은 아동 권한 하나만 가진다 — 역할이 없어도 권한을 만들다 멈추지 않는다")
    void childAuthorities() {
        AuthMember child = AuthMember.child(3L);

        assertThat(child.scope()).isEqualTo(TokenScope.CHILD);
        assertThat(child.authorities()).extracting(GrantedAuthority::getAuthority).containsExactly("ROLE_CHILD");
    }

    @Test
    @DisplayName("아동 번호는 회원 번호 칸에 넣지 않는다 — 두 번호는 값이 겹쳐, 회원 번호로 잘못 읽히면 남의 회원이 된다")
    void childIdIsNotMemberId() {
        AuthMember child = AuthMember.child(3L);

        assertThat(child.childId()).isEqualTo(3L);
        assertThat(child.memberId()).isNull();
        assertThat(child.role()).isNull();
    }

    @Test
    @DisplayName("점주·가입 상태 확인용 사용자에게는 아동 번호가 없다")
    void nonChildHasNoChildId() {
        assertThat(new AuthMember(3L, MemberRole.OWNER).childId()).isNull();
        assertThat(AuthMember.signupStatus(3L).childId()).isNull();
    }

    @Test
    @DisplayName("용도와 칸이 섞인 사용자는 만들 수 없다 — 역할이 있는 아동, 아동 번호가 있는 점주, 역할 없는 일반 사용자")
    void rejectsMixedShapes() {
        assertThatThrownBy(() -> new AuthMember(null, MemberRole.ADMIN, TokenScope.CHILD, 3L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AuthMember(1L, null, TokenScope.CHILD, 3L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AuthMember(null, null, TokenScope.CHILD, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AuthMember(1L, MemberRole.OWNER, TokenScope.ACCESS, 3L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AuthMember(1L, null, TokenScope.ACCESS, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AuthMember(1L, MemberRole.OWNER, TokenScope.SIGNUP_STATUS, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AuthMember(1L, MemberRole.OWNER, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("아동 권한 이름은 어떤 역할 이름·가입 상태 권한과도 겹치지 않는다")
    void childRoleDoesNotCollide() {
        assertThat(MemberRole.values()).extracting(Enum::name).doesNotContain(AuthMember.CHILD_ROLE);
        assertThat(AuthMember.CHILD_ROLE).isNotEqualTo(AuthMember.SIGNUP_STATUS_ROLE);
    }
}
