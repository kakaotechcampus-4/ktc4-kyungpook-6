package com.ktc4.backend.domain.task.repository;

import com.ktc4.backend.domain.task.entity.Task;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface TaskRepository extends JpaRepository<Task, Long> {

    /**
     * 조사 한 건의 Task 를 가게와 함께 읽는다 — 화면이 폴링마다 부르므로 가게를 한 쿼리로 같이 가져온다(N+1 방지).
     *
     * @param jobId 조사 ID
     * @return taskId 오름차순 Task (가게가 채워진 채)
     */
    @Query("""
            select t from Task t
              join fetch t.store
             where t.job.jobId = :jobId
             order by t.taskId asc
            """)
    List<Task> findAllWithStoreByJobId(@Param("jobId") Long jobId);

    /**
     * Task 한 건을 행 잠금({@code SELECT … FOR UPDATE})으로 읽는다. 같은 Task 를 먼저 잠근 트랜잭션이 끝날 때까지 기다린다.
     *
     * <p>가게는 join fetch 하지 않는다 — 함께 읽으면 가게 행까지 잠가, 같은 가게의 다른 조사 결과·가게 수정까지 기다리게 된다.
     *
     * @param taskId Task ID
     * @return 잠근 Task. 없으면 비어 있다
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from Task t where t.taskId = :taskId")
    Optional<Task> findByIdForUpdate(@Param("taskId") Long taskId);
}
