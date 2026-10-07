package com.ktc4.backend.domain.task.repository;

import com.ktc4.backend.domain.task.entity.Task;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

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
}
