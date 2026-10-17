package com.ktc4.backend.domain.store.repository;

import com.ktc4.backend.domain.store.dto.BusinessHourMinutes;
import com.ktc4.backend.domain.store.entity.StoreBusinessHour;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface StoreBusinessHourRepository extends JpaRepository<StoreBusinessHour, Long> {

    boolean existsByStoreStoreId(Long storeId);

    List<StoreBusinessHour> findByStoreStoreId(Long storeId);

    /**
     * 가게의 영업시간을 값만 읽는다 — 엔티티를 영속성 컨텍스트에 올리지 않는다. 벌크 삭제({@link #deleteAllByStoreId})와
     * 같은 트랜잭션에서 영업시간을 읽어야 할 때(변경 기록의 스냅숏) 쓴다.
     *
     * @param storeId 가게 ID
     * @return 영업시간 값 (순서 없음)
     */
    @Query("select new com.ktc4.backend.domain.store.dto.BusinessHourMinutes(h.day, h.openMinute, h.closeMinute)"
            + " from StoreBusinessHour h where h.store.storeId = :storeId")
    List<BusinessHourMinutes> findMinutesByStoreId(@Param("storeId") Long storeId);

    /**
     * 가게의 영업시간을 한 번의 {@code delete} 문으로 모두 지운다 — 운영 정보를 통째로 바꿀 때 새 줄을 넣기 전에 부른다.
     * 이 가게를 잠근 뒤에 부르고, 바꾸기 전·후를 변경 기록에 남긴다({@code StoreChangeHistoryService}).
     *
     * <p>파생 메서드({@code deleteBy…})는 줄을 하나씩 읽어 지우고, Hibernate 가 넣기를 지우기보다 먼저 내보낼 수 있어
     * 쓰지 않는다. 영속성 컨텍스트를 거치지 않으므로, 같은 트랜잭션에서 이 가게의 영업시간 엔티티를 미리 읽어 두면
     * 안 된다.
     *
     * <p>⚠️ {@code clearAutomatically} 를 켜지 않는다. 켜면 영속성 컨텍스트가 비워져, 이 호출 전에 바꿔 둔 가게
     * 전화·메뉴가 저장되지 않고 조용히 사라진다.
     *
     * @param storeId 가게 ID
     * @return 지운 줄 수
     */
    @Modifying
    @Query("delete from StoreBusinessHour h where h.store.storeId = :storeId")
    int deleteAllByStoreId(@Param("storeId") Long storeId);
}
