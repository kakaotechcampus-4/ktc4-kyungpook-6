package com.ktc4.backend.domain.member.repository;

import com.ktc4.backend.domain.member.entity.Member;
import com.ktc4.backend.domain.member.enums.MemberRole;
import com.ktc4.backend.domain.member.enums.MemberStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface MemberRepository extends JpaRepository<Member, Long> {

    /**
     * @param email {@code Member.normalizeEmail} 로 맞춘 이메일
     * @return 그 이메일의 회원
     */
    Optional<Member> findByEmail(String email);

    /**
     * @param email {@code Member.normalizeEmail} 로 맞춘 이메일
     * @return 그 이메일로 가입한 회원이 있는지
     */
    boolean existsByEmail(String email);

    /**
     * 역할·상태로 회원을 페이지 단위로 읽는다. 정렬은 {@code pageable} 로 받는다.
     *
     * @param role     역할
     * @param status   상태
     * @param pageable 페이지·정렬 정보
     * @return 조건에 맞는 회원
     */
    Page<Member> findByRoleAndStatus(MemberRole role, MemberStatus status, Pageable pageable);
}
