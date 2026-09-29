package com.ktc4.backend.global.security;

import com.ktc4.backend.domain.member.entity.Member;
import com.ktc4.backend.domain.member.enums.MemberRole;
import com.ktc4.backend.domain.member.repository.MemberRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 기동할 때 관리자 계정이 없으면 환경변수({@code ADMIN_EMAIL}, {@code ADMIN_PASSWORD})로 한 번 만든다.
 *
 * <p>관리자는 가입 기능 없이 한 계정만 둔다. 비밀번호를 코드나 설정 파일에 두지 않으려고 환경변수로 받는다.
 * 이미 있으면 건드리지 않는다 — 환경변수를 바꿔도 기존 비밀번호가 조용히 덮이지 않게 하기 위해서다.
 */
@Slf4j
@Component
public class AdminAccountInitializer implements ApplicationRunner {

    static final int MIN_PASSWORD_LENGTH = 8;

    private final MemberRepository memberRepository;
    private final PasswordEncoder passwordEncoder;
    private final String email;
    private final String password;

    public AdminAccountInitializer(MemberRepository memberRepository,
                                   PasswordEncoder passwordEncoder,
                                   @Value("${auth.admin.email:}") String email,
                                   @Value("${auth.admin.password:}") String password) {
        this.memberRepository = memberRepository;
        this.passwordEncoder = passwordEncoder;
        this.email = email;
        this.password = password;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (email == null || email.isBlank() || password == null || password.isBlank()) {
            log.warn("ADMIN_EMAIL / ADMIN_PASSWORD 가 없어 관리자 계정을 만들지 않습니다");
            return;
        }
        if (password.length() < MIN_PASSWORD_LENGTH) {
            log.warn("ADMIN_PASSWORD 가 {}자보다 짧아 관리자 계정을 만들지 않습니다", MIN_PASSWORD_LENGTH);
            return;
        }

        memberRepository.findByEmail(Member.normalizeEmail(email)).ifPresentOrElse(
                existing -> {
                    if (existing.getRole() != MemberRole.ADMIN) {
                        log.warn("ADMIN_EMAIL 이 관리자가 아닌 계정으로 이미 가입돼 있어 관리자 계정을 만들지 않습니다");
                    }
                },
                () -> {
                    memberRepository.save(Member.admin(email, passwordEncoder.encode(password)));
                    // 이메일·비밀번호는 남기지 않는다.
                    log.info("관리자 계정을 만들었습니다");
                });
    }
}
