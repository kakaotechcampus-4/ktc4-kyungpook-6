package com.ktc4.backend.domain.member.repository;

import com.ktc4.backend.domain.member.entity.Member;
import com.ktc4.backend.support.PostgresContainerTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
    void 대소문자만_다른_같은_이메일은_두_번_저장할_수_없다() {
        entityManager.persistAndFlush(Member.admin("admin@example.com", "hash"));

        assertThatThrownBy(() -> memberRepository.saveAndFlush(Member.admin("ADMIN@example.com", "hash")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
