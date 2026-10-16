package com.ktc4.backend.domain.checkin.repository;

import com.ktc4.backend.domain.checkin.entity.CheckIn;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CheckInRepository extends JpaRepository<CheckIn, Long> {

    /**
     * 그 가게에 방문 기록이 하나라도 있는지 — 가게를 지워도 되는지 판단하는 데 쓴다.
     *
     * <p>체크인은 아동이 실제로 그 가게에 다녀간 기록이라, 가게를 지우면 같이 사라진다.
     * 그래서 하나라도 있으면 삭제를 거절한다({@code STORE_HAS_CHECK_IN}).
     *
     * @param storeId 가게 ID
     * @return 방문 기록이 있으면 true
     */
    boolean existsByStore_StoreId(Long storeId);
}
