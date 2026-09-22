package com.ktc4.backend.domain.store.repository;

import com.ktc4.backend.domain.store.dto.StoreWithNtsCheck;
import com.ktc4.backend.domain.store.entity.Store;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface StoreRepository extends JpaRepository<Store, Long> {

    /**
     * 가게와 국세청 확인 기록을 함께 읽는다.
     *
     * <p>아직 확인되지 않은 가게도 빠지지 않도록 바깥 조인으로 읽는다 — 그런 가게는 기록이 null 이다.
     *
     * @param pageable 페이지 정보
     * @return 가게별 확인 기록. 기록이 없으면 {@code check} 가 null
     */
    @Query("""
            select new com.ktc4.backend.domain.store.dto.StoreWithNtsCheck(s, c)
            from Store s
            left join StoreNtsCheck c on c.store = s
            order by s.storeId asc
            """)
    Page<StoreWithNtsCheck> findAllWithNtsCheck(Pageable pageable);

    /**
     * 우리 DB 상태와 국세청 상태가 서로 다른 가게만 읽는다 — AI 조사 대상이다.
     *
     * <p>거르는 규칙은 {@code StatusComparison.isMismatch()} 와 같아야 한다. 국세청 미등록은
     * 상태가 다른 게 아니라 번호가 틀린 것이라 제외하고({@code findDataProblem} 이 맡는다),
     * 우리 상태가 UNKNOWN 이거나 국세청을 확인하지 못한 가게도 비교할 수 없으므로 제외한다.
     *
     * @param pageable 페이지 정보
     * @return 상태가 다른 가게 목록
     */
    @Query("""
            select new com.ktc4.backend.domain.store.dto.StoreWithNtsCheck(s, c)
            from Store s
            join StoreNtsCheck c on c.store = s
            where c.checkResult = com.ktc4.backend.domain.store.enums.NtsLookupResult.CONFIRMED
              and c.ntsState is not null
              and c.ntsState <> com.ktc4.backend.domain.business.enums.BusinessState.NOT_REGISTERED
              and s.status <> com.ktc4.backend.domain.store.enums.StoreStatus.UNKNOWN
              and ((s.status = com.ktc4.backend.domain.store.enums.StoreStatus.OPEN
                        and c.ntsState <> com.ktc4.backend.domain.business.enums.BusinessState.ACTIVE)
                or (s.status = com.ktc4.backend.domain.store.enums.StoreStatus.SUSPENDED
                        and c.ntsState <> com.ktc4.backend.domain.business.enums.BusinessState.SUSPENDED)
                or (s.status = com.ktc4.backend.domain.store.enums.StoreStatus.CLOSED
                        and c.ntsState <> com.ktc4.backend.domain.business.enums.BusinessState.CLOSED))
            order by s.storeId asc
            """)
    Page<StoreWithNtsCheck> findStatusMismatch(Pageable pageable);

    /**
     * 우리 DB 의 사업자등록번호가 없거나 틀린 가게만 읽는다 — 사람이 데이터를 고쳐야 하는 대상이다.
     *
     * <p>조회 실패(UNCONFIRMED)는 다시 조회하면 확인될 수 있어 데이터 문제로 보지 않는다.
     *
     * @param pageable 페이지 정보
     * @return 번호가 없거나(NO_BIZ_NO) 국세청에 없는(NOT_REGISTERED) 가게 목록
     */
    @Query("""
            select new com.ktc4.backend.domain.store.dto.StoreWithNtsCheck(s, c)
            from Store s
            join StoreNtsCheck c on c.store = s
            where c.checkResult = com.ktc4.backend.domain.store.enums.NtsLookupResult.NO_BIZ_NO
               or c.ntsState = com.ktc4.backend.domain.business.enums.BusinessState.NOT_REGISTERED
            order by s.storeId asc
            """)
    Page<StoreWithNtsCheck> findDataProblem(Pageable pageable);
}
