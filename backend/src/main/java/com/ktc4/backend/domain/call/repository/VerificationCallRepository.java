package com.ktc4.backend.domain.call.repository;

import com.ktc4.backend.domain.call.entity.VerificationCall;
import com.ktc4.backend.domain.call.enums.CallStatus;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface VerificationCallRepository extends JpaRepository<VerificationCall, Long> {

    /** 조사 결과 여러 건의 전화 기록을 한 쿼리로 읽는다 — 결과 화면이 폴링마다 부른다(N+1 방지). */
    List<VerificationCall> findByTask_TaskIdIn(Collection<Long> taskIds);

    /**
     * 아직 끝나지 않은 전화의 ID 를 오래된 것부터 읽는다. 폴러가 이 ID 로 한 건씩 갱신한다.
     *
     * <p>엔티티가 아니라 ID 만 읽는 이유는 갱신을 건별 트랜잭션으로 하기 때문이다 — 한 트랜잭션에
     * 묶으면 전화 API 호출 여러 번이 DB 커넥션 하나를 그만큼 붙들고 있게 된다.
     *
     * @param statuses 아직 끝나지 않은 상태들
     * @param limit    한 번에 가져올 수
     * @return 전화 ID. 없으면 빈 목록
     */
    @Query("select c.callId from VerificationCall c where c.status in :statuses order by c.requestedAt asc")
    List<Long> findIdsByStatusIn(@Param("statuses") Collection<CallStatus> statuses, Limit limit);

    /**
     * 그 조사 결과에 끝나지 않았거나 확인을 마친 전화가 있는가 — 같은 카드에 전화를 두 번 걸지 않으려고 본다.
     *
     * <p>실패한 전화는 세지 않는다. 안 받았거나 끊긴 전화는 다시 걸 수 있어야 한다.
     *
     * @param taskId 조사 결과 ID
     * @return 다시 걸 수 없는 전화가 있으면 true
     */
    @Query("""
            select count(c) > 0 from VerificationCall c
             where c.task.taskId = :taskId
               and c.status <> com.ktc4.backend.domain.call.enums.CallStatus.FAILED
            """)
    boolean existsLiveByTaskId(@Param("taskId") Long taskId);

    /**
     * 그 가게의 전화 기록을 한 번의 {@code delete} 문으로 지운다 — 가게를 지울 때 부른다.
     *
     * <p>전화 기록은 가게와 조사 결과를 <b>둘 다</b> 가리킨다. {@code VerificationRepository} 와 같은
     * 이유로 {@link #deleteAllByTaskIds} 를 함께 부른다.
     *
     * @param storeId 가게 ID
     * @return 지운 줄 수
     */
    @Modifying
    @Query("delete from VerificationCall c where c.store.storeId = :storeId")
    int deleteAllByStoreId(@Param("storeId") Long storeId);

    /**
     * 조사 결과 여러 건의 전화 기록을 한 번의 {@code delete} 문으로 지운다.
     *
     * @param taskIds 조사 결과 ID. 비어 있으면 아무것도 지우지 않는다
     * @return 지운 줄 수
     */
    @Modifying
    @Query("delete from VerificationCall c where c.task.taskId in :taskIds")
    int deleteAllByTaskIds(@Param("taskIds") Collection<Long> taskIds);
}
