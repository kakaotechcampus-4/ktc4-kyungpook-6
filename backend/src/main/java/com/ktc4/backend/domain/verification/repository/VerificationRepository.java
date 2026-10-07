package com.ktc4.backend.domain.verification.repository;

import com.ktc4.backend.domain.verification.entity.Verification;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface VerificationRepository extends JpaRepository<Verification, Long> {

    boolean existsByTask_TaskId(Long taskId);

    /** 조사 결과 여러 건의 확인 기록을 한 쿼리로 읽는다 — 결과 화면이 폴링마다 부른다(N+1 방지). */
    List<Verification> findByTask_TaskIdIn(Collection<Long> taskIds);
}
