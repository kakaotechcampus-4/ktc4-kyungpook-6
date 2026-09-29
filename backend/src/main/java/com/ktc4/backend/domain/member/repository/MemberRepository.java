package com.ktc4.backend.domain.member.repository;

import com.ktc4.backend.domain.member.entity.Member;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface MemberRepository extends JpaRepository<Member, Long> {

    /**
     * @param email {@code Member.normalizeEmail} 로 맞춘 이메일
     * @return 그 이메일의 회원
     */
    Optional<Member> findByEmail(String email);
}
