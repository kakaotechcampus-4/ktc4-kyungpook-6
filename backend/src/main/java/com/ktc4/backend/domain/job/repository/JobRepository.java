package com.ktc4.backend.domain.job.repository;

import com.ktc4.backend.domain.job.entity.Job;
import com.ktc4.backend.domain.job.enums.JobStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Optional;

public interface JobRepository extends JpaRepository<Job, Long> {

    /** 가장 최근에 만든 조사 — ID 가 만든 순서대로 커진다. */
    Optional<Job> findFirstByOrderByJobIdDesc();

    /**
     * 주어진 시각 전에 만들어져 아직 끝나지 않은 조사를 한 번에 실패로 바꾼다 — 서버 재시작 정리용이다.
     *
     * <p>벌크 UPDATE 는 영속성 컨텍스트와 JPA Auditing 을 거치지 않으므로 {@code updatedAt} 까지 직접 채우고,
     * 실행 뒤 영속성 컨텍스트를 비워 옛 값을 읽지 않게 한다.
     *
     * @param statuses     정리할 상태 (대기·진행 중)
     * @param createdBefore 이 시각 전에 만든 조사만
     * @param errorMessage 남길 실패 이유
     * @param failedAt     실패로 기록할 시각
     * @return 바꾼 조사 수
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update Job j
               set j.status = com.ktc4.backend.domain.job.enums.JobStatus.FAILED,
                   j.errorMessage = :errorMessage,
                   j.finishedAt = :failedAt,
                   j.updatedAt = :failedAt
             where j.status in :statuses
               and j.createdAt < :createdBefore
            """)
    int failUnfinishedCreatedBefore(@Param("statuses") Collection<JobStatus> statuses,
                                    @Param("createdBefore") LocalDateTime createdBefore,
                                    @Param("errorMessage") String errorMessage,
                                    @Param("failedAt") LocalDateTime failedAt);
}
