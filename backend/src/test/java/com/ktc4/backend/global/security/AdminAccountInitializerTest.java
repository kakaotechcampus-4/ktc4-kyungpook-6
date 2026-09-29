package com.ktc4.backend.global.security;

import com.ktc4.backend.domain.member.entity.Member;
import com.ktc4.backend.domain.member.enums.MemberRole;
import com.ktc4.backend.domain.member.enums.MemberStatus;
import com.ktc4.backend.domain.member.repository.MemberRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("AdminAccountInitializer")
class AdminAccountInitializerTest {

    private static final PasswordEncoder ENCODER = new BCryptPasswordEncoder(4);
    private static final String PASSWORD = "admin-password";

    @Mock
    private MemberRepository memberRepository;

    private void run(String email, String password) {
        new AdminAccountInitializer(memberRepository, ENCODER, email, password)
                .run(new DefaultApplicationArguments());
    }

    @Test
    @DisplayName("관리자가 없으면 암호화한 비밀번호로 승인된 관리자를 만든다")
    void createsAdmin() {
        when(memberRepository.findByEmail("admin@example.com")).thenReturn(Optional.empty());

        run(" Admin@Example.com ", PASSWORD);

        ArgumentCaptor<Member> saved = ArgumentCaptor.forClass(Member.class);
        verify(memberRepository).save(saved.capture());
        assertThat(saved.getValue().getEmail()).isEqualTo("admin@example.com");
        assertThat(saved.getValue().getRole()).isEqualTo(MemberRole.ADMIN);
        assertThat(saved.getValue().getStatus()).isEqualTo(MemberStatus.APPROVED);
        assertThat(saved.getValue().getPasswordHash()).isNotEqualTo(PASSWORD);
        assertThat(ENCODER.matches(PASSWORD, saved.getValue().getPasswordHash())).isTrue();
    }

    @Test
    @DisplayName("이미 있으면 비밀번호를 덮어쓰지 않는다")
    void keepsExistingAdmin() {
        when(memberRepository.findByEmail("admin@example.com"))
                .thenReturn(Optional.of(Member.admin("admin@example.com", "existing-hash")));

        run("admin@example.com", PASSWORD);

        verify(memberRepository, never()).save(any());
    }

    @Test
    @DisplayName("같은 이메일이 점주로 가입돼 있으면 관리자로 바꾸지 않는다")
    void skipsWhenEmailBelongsToOwner() {
        Member owner = Member.admin("admin@example.com", "owner-hash");
        ReflectionTestUtils.setField(owner, "role", MemberRole.OWNER);
        when(memberRepository.findByEmail("admin@example.com")).thenReturn(Optional.of(owner));

        run("admin@example.com", PASSWORD);

        verify(memberRepository, never()).save(any());
    }

    @ParameterizedTest
    @CsvSource(value = {"'', admin-password", "admin@example.com, ''", "NULL, NULL", "admin@example.com, short"},
            nullValues = "NULL")
    @DisplayName("값이 없거나 비밀번호가 8자보다 짧으면 만들지 않는다")
    void skipsWhenNotConfigured(String email, String password) {
        run(email, password);

        verifyNoInteractions(memberRepository);
    }
}
