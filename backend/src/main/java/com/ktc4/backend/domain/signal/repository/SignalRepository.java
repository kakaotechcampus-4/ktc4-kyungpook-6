package com.ktc4.backend.domain.signal.repository;

import com.ktc4.backend.domain.signal.entity.Signal;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface SignalRepository extends JpaRepository<Signal, Long> {

    List<Signal> findByTask_TaskIdInOrderBySignalIdAsc(Collection<Long> taskIds);

    /**
     * 조사 결과 여러 건에 딸린 신호를 한 번의 {@code delete} 문으로 지운다 — 가게를 지울 때 부른다.
     *
     * <p>신호는 조사 결과(task)를 가리키므로, 조사 결과보다 먼저 지워야 외래키에 걸리지 않는다.
     * 파생 메서드({@code deleteBy…})가 아니라 {@code @Modifying} 질의를 쓰는 이유는
     * {@code StoreBusinessHourRepository#deleteAllByStoreId} 와 같다.
     *
     * @param taskIds 조사 결과 ID. 비어 있으면 아무것도 지우지 않는다
     * @return 지운 줄 수
     */
    @Modifying
    @Query("delete from Signal s where s.task.taskId in :taskIds")
    int deleteAllByTaskIds(@Param("taskIds") Collection<Long> taskIds);
}
