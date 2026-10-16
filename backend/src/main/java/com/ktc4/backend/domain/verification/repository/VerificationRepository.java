package com.ktc4.backend.domain.verification.repository;

import com.ktc4.backend.domain.verification.entity.Verification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface VerificationRepository extends JpaRepository<Verification, Long> {

    boolean existsByTask_TaskId(Long taskId);

    /** 조사 결과 여러 건의 확인 기록을 한 쿼리로 읽는다 — 결과 화면이 폴링마다 부른다(N+1 방지). */
    List<Verification> findByTask_TaskIdIn(Collection<Long> taskIds);

    /**
     * 그 가게의 확인 기록을 한 번의 {@code delete} 문으로 지운다 — 가게를 지울 때 부른다.
     *
     * <p>확인 기록은 가게와 조사 결과를 <b>둘 다</b> 가리킨다. 그래서 가게로 지우는 것만으로는
     * 모자랄 수 있다 — 지울 가게의 조사 결과를 가리키면서 가게 칸은 다른 가게인 행이 있으면
     * 조사 결과를 지울 때 외래키에 걸린다. {@link #deleteAllByTaskIds} 를 함께 부른다.
     *
     * @param storeId 가게 ID
     * @return 지운 줄 수
     */
    @Modifying
    @Query("delete from Verification v where v.store.storeId = :storeId")
    int deleteAllByStoreId(@Param("storeId") Long storeId);

    /**
     * 조사 결과 여러 건의 확인 기록을 한 번의 {@code delete} 문으로 지운다.
     *
     * @param taskIds 조사 결과 ID. 비어 있으면 아무것도 지우지 않는다
     * @return 지운 줄 수
     */
    @Modifying
    @Query("delete from Verification v where v.task.taskId in :taskIds")
    int deleteAllByTaskIds(@Param("taskIds") Collection<Long> taskIds);
}
