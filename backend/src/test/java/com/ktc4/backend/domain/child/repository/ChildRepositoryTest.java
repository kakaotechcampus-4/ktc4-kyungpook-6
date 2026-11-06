package com.ktc4.backend.domain.child.repository;

import com.ktc4.backend.domain.child.entity.Child;
import com.ktc4.backend.support.PostgresContainerTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 아동 표를 실제 Postgres 에 붙여서 확인한다. 넣은 뒤 {@code clear} 하고 다시 읽는다 — 1차 캐시가 아니라 DB 를 읽게.
 */
@DisplayName("ChildRepository")
class ChildRepositoryTest extends PostgresContainerTest {

    private static final String HASH = "a".repeat(64);
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 3, 12, 0);

    @Autowired
    private ChildRepository childRepository;

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private int rowsWith(String cardHash) {
        Integer count = jdbcTemplate.queryForObject("SELECT count(*) FROM child WHERE card_hash = ?", Integer.class, cardHash);
        return count == null ? 0 : count;
    }

    @Test
    @DisplayName("넣은 아동을 해시로 찾고, 시각 칸도 넘긴 값 그대로 저장된다")
    void insertsAndFindsByHash() {
        assertThat(childRepository.insertIfAbsent(HASH, NOW)).isEqualTo(1);
        entityManager.clear();

        Long childId = childRepository.findIdByCardHash(HASH).orElseThrow();
        Child child = childRepository.findById(childId).orElseThrow();
        assertThat(child.getCardHash()).isEqualTo(HASH);
        assertThat(child.getCreatedAt()).isEqualTo(NOW);
        assertThat(child.getUpdatedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("같은 해시로 다시 넣으면 아무 일도 없다 — 예외 없이 0건, 행은 하나, 번호도 그대로")
    void insertIsIdempotent() {
        childRepository.insertIfAbsent(HASH, NOW);
        Long first = childRepository.findIdByCardHash(HASH).orElseThrow();

        assertThat(childRepository.insertIfAbsent(HASH, NOW.plusDays(1))).isZero();
        entityManager.clear();

        assertThat(rowsWith(HASH)).isEqualTo(1);
        assertThat(childRepository.findIdByCardHash(HASH)).contains(first);
    }

    @Test
    @DisplayName("다른 해시는 다른 아동이다")
    void differentHashDifferentChild() {
        childRepository.insertIfAbsent(HASH, NOW);
        childRepository.insertIfAbsent("b".repeat(64), NOW);

        Long first = childRepository.findIdByCardHash(HASH).orElseThrow();
        Long second = childRepository.findIdByCardHash("b".repeat(64)).orElseThrow();
        assertThat(second).isNotEqualTo(first);
    }

    @Test
    @DisplayName("없는 해시는 빈 값이다")
    void missingHash() {
        assertThat(childRepository.findIdByCardHash("c".repeat(64))).isEmpty();
    }
}
