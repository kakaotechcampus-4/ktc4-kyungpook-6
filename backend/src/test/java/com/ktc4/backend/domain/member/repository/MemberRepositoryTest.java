package com.ktc4.backend.domain.member.repository;

import com.ktc4.backend.domain.member.entity.Member;
import com.ktc4.backend.domain.member.entity.OwnerInfo;
import com.ktc4.backend.domain.member.enums.MemberRole;
import com.ktc4.backend.domain.member.enums.MemberStatus;
import com.ktc4.backend.support.PostgresContainerTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

// 계정 정보는 모두 가짜 값이다.
class MemberRepositoryTest extends PostgresContainerTest {

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private TestEntityManager entityManager;

    @Test
    void 이메일은_맞춘_표기로_저장되고_그_값으로_찾는다() {
        entityManager.persistAndFlush(Member.admin(" Admin@Example.com ", "hash"));
        entityManager.clear();

        assertThat(memberRepository.findByEmail("admin@example.com"))
                .get().extracting(Member::getEmail).isEqualTo("admin@example.com");
    }

    @Test
    void 대소문자만_다른_같은_이메일은_두_번_저장할_수_없고_제약_이름이_나온다() {
        entityManager.persistAndFlush(Member.admin("admin@example.com", "hash"));

        Throwable thrown = catchThrowable(() -> memberRepository.saveAndFlush(Member.admin("ADMIN@example.com", "hash")));

        assertThat(thrown).isInstanceOf(DataIntegrityViolationException.class);
        // 가입 서비스가 이 제약 이름으로 "이미 가입된 이메일"을 구분한다 — 실제 Postgres 가 이 이름을 돌려주는지 고정
        Throwable cause = thrown;
        while (cause != null && !(cause instanceof org.hibernate.exception.ConstraintViolationException)) {
            cause = cause.getCause();
        }
        assertThat(cause).isInstanceOf(org.hibernate.exception.ConstraintViolationException.class);
        assertThat(((org.hibernate.exception.ConstraintViolationException) cause).getConstraintName())
                .isEqualToIgnoringCase(Member.EMAIL_UNIQUE_CONSTRAINT);
    }

    @Test
    void 제약_위반_예외_메시지에_입력값이_담기지_않는다() {
        // application.yml 의 logServerErrorDetail=false 가 실제로 적용되는지 확인한다. 꺼져 있지 않으면
        // 드라이버가 "Key (email)=(admin@example.com) already exists" 를 메시지에 넣고, Hibernate 가 그대로 ERROR 로그로 남긴다.
        entityManager.persistAndFlush(Member.admin("admin@example.com", "hash"));

        Throwable thrown = catchThrowable(() -> memberRepository.saveAndFlush(Member.admin("admin@example.com", "hash")));

        assertThat(thrown).isInstanceOf(DataIntegrityViolationException.class);
        for (Throwable cause = thrown; cause != null; cause = cause.getCause()) {
            assertThat(String.valueOf(cause.getMessage())).doesNotContain("admin@example.com");
        }
    }

    private static Member owner(String email) {
        return Member.ownerApplicant(email, "hash", new OwnerInfo("1234567890", "예시분식", "홍길동"));
    }

    @Test
    void 점주_정보는_회원과_함께_저장되고_관리자는_비어_있다() {
        Long ownerId = entityManager.persistAndFlush(owner("owner@example.com")).getMemberId();
        Long adminId = entityManager.persistAndFlush(Member.admin("admin@example.com", "hash")).getMemberId();
        entityManager.clear();

        Member owner = memberRepository.findById(ownerId).orElseThrow();
        assertThat(owner.getOwnerInfo().getBizNo()).isEqualTo("1234567890");
        assertThat(owner.getOwnerInfo().getStoreName()).isEqualTo("예시분식");
        assertThat(owner.getOwnerInfo().getRepresentativeName()).isEqualTo("홍길동");
        assertThat(memberRepository.findById(adminId).orElseThrow().getOwnerInfo()).isNull();
    }

    @Test
    void 역할과_상태로_거른_목록만_가져온다() {
        entityManager.persist(owner("pending1@example.com"));
        Member approved = owner("approved@example.com");
        approved.approve(LocalDateTime.of(2026, 9, 29, 10, 0));
        entityManager.persist(approved);
        entityManager.persist(owner("pending2@example.com"));
        entityManager.persist(Member.admin("admin@example.com", "hash"));
        entityManager.flush();
        entityManager.clear();

        var pending = memberRepository.findByRoleAndStatus(MemberRole.OWNER, MemberStatus.PENDING,
                PageRequest.of(0, 10, Sort.by("memberId")));

        assertThat(pending.getContent()).extracting(Member::getEmail)
                .containsExactly("pending1@example.com", "pending2@example.com");
        assertThat(pending.getTotalElements()).isEqualTo(2);
    }

    @Test
    void 이메일로_가입_여부를_확인한다() {
        entityManager.persistAndFlush(owner("owner@example.com"));

        assertThat(memberRepository.existsByEmail("owner@example.com")).isTrue();
        assertThat(memberRepository.existsByEmail("nobody@example.com")).isFalse();
    }
}
