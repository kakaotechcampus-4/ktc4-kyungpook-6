package com.ktc4.backend.domain.member.repository;

import com.ktc4.backend.domain.member.entity.Member;
import com.ktc4.backend.domain.member.enums.MemberRole;
import com.ktc4.backend.domain.member.enums.MemberStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    /**
     * 회원을 잠그고 읽는다 — 상태를 확인한 뒤 바꾸는 사이에 다른 요청이 끼어들지 못하게 한다.
     *
     * <p>가입 승인에 쓴다. 잠그지 않으면 같은 신청을 동시에 두 번 승인해 가게 연결이 두 건 생길 수 있다.
     * 트랜잭션 안에서만 부른다.
     *
     * @param memberId 회원 ID
     * @return 그 회원
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from Member m where m.memberId = :memberId")
    Optional<Member> findByIdForUpdate(@Param("memberId") Long memberId);
}
