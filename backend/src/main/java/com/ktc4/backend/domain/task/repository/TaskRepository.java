package com.ktc4.backend.domain.task.repository;

import com.ktc4.backend.domain.job.enums.JobStatus;
import com.ktc4.backend.domain.task.entity.Task;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface TaskRepository extends JpaRepository<Task, Long> {

    /**
     * 그 가게의 조사 결과 ID 를 읽는다 — 가게를 지울 때, 조사 결과에 딸린 신호·확인 기록을 먼저 지우려고 부른다.
     *
     * <p>엔티티를 다 읽지 않고 ID 만 읽는다. 지우기 전 한 번 쓰고 버리는 값이라 나머지 칸이 필요 없다.
     *
     * @param storeId 가게 ID
     * @return 조사 결과 ID. 없으면 빈 목록
     */
    @Query("select t.taskId from Task t where t.store.storeId = :storeId")
    List<Long> findTaskIdsByStoreId(@Param("storeId") Long storeId);

    /**
     * 그 가게가 아직 끝나지 않은 조사에 들어 있는지 — 가게를 지워도 되는지 판단하는 데 쓴다.
     *
     * <p>돌고 있는 조사의 대상을 지우면 AI 서버가 결과를 돌려줄 때 가리킬 가게가 없어진다.
     * 그래서 {@code PENDING}·{@code IN_PROGRESS} 조사에 들어 있으면 삭제를 거절한다
     * ({@code STORE_UNDER_INVESTIGATION}). 끝난 조사({@code DONE}·{@code FAILED})는 막지 않는다.
     *
     * @param storeId  가게 ID
     * @param statuses 끝나지 않은 것으로 볼 조사 상태
     * @return 그런 조사가 있으면 true
     */
    @Query("""
            select count(t) > 0
            from Task t
            where t.store.storeId = :storeId and t.job.status in :statuses
            """)
    boolean existsByStoreIdAndJobStatusIn(@Param("storeId") Long storeId,
                                          @Param("statuses") Collection<JobStatus> statuses);

    /**
     * 그 가게의 조사 결과를 한 번의 {@code delete} 문으로 지운다 — 가게를 지울 때 부른다.
     *
     * <p>신호·확인 기록이 이 행을 가리키므로 그 둘을 먼저 지워야 한다.
     * 조사(job) 자체는 남는다 — 어떤 조사를 언제 돌렸는지는 가게와 별개의 기록이다.
     *
     * @param storeId 가게 ID
     * @return 지운 줄 수
     */
    @Modifying
    @Query("delete from Task t where t.store.storeId = :storeId")
    int deleteAllByStoreId(@Param("storeId") Long storeId);

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
     * <p>가게는 join fetch 하지 않는다 — 함께 읽으면 가게 행까지 잠가, 가게를 바꾸지 않는 확인 완료도 같은 가게의 다른
     * 조사 결과·가게 수정을 기다리게 된다. 수정안 반영은 조사 결과 → 가게 순서로 잠그는데, 가게는
     * {@code StoreService#updateStore} 가 처음 읽으며 잠근다(여기서 가게를 먼저 읽어 두면 그 뒤 잠금 조회가 낡은 값을 준다).
     *
     * @param taskId Task ID
     * @return 잠근 Task. 없으면 비어 있다
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from Task t where t.taskId = :taskId")
    Optional<Task> findByIdForUpdate(@Param("taskId") Long taskId);
}
