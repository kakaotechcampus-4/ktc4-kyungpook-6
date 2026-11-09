package com.ktc4.backend.domain.child.repository;

import com.ktc4.backend.domain.child.entity.Child;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

public interface ChildRepository extends JpaRepository<Child, Long> {

    @Query("SELECT c.childId FROM Child c WHERE c.cardHash = :cardHash")
    Optional<Long> findIdByCardHash(@Param("cardHash") String cardHash);

    /**
     * 그 해시의 아동이 없으면 넣고, 이미 있으면 아무것도 하지 않는다.
     *
     * <p>"찾고 없으면 저장"으로 하면 같은 카드로 동시에 처음 인증할 때 하나가 unique 제약에 걸리고, Postgres 는 그 뒤
     * 같은 트랜잭션의 조회를 거절한다("current transaction is aborted"). {@code ON CONFLICT DO NOTHING} 은 예외를 내지 않고,
     * 다른 트랜잭션이 같은 값을 넣는 중이면 그 커밋을 기다렸다가 넘어가므로, 바로 뒤의 조회가 그 행을 본다.
     *
     * <p>네이티브 쿼리라 JPA Auditing 이 시각을 채우지 않는다 — 부르는 쪽이 넘긴다.
     *
     * @param cardHash 카드 번호의 해시
     * @param now      행 생성·수정 시각
     * @return 넣었으면 1, 이미 있었으면 0
     */
    @Modifying
    @Query(value = """
            INSERT INTO child (card_hash, created_at, updated_at)
            VALUES (:cardHash, :now, :now)
            ON CONFLICT (card_hash) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("cardHash") String cardHash, @Param("now") LocalDateTime now);
}
